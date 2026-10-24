package com.setu.aiexplain.application;

import com.setu.aiexplain.infrastructure.ratelimit.AiRateLimiter;
import com.setu.shared.aifacts.AiFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Day 14 §AI: the orchestration layer — see the architecture flow in the
 * canonical spec §10. A single {@code POST /ai/chat} (or {@code /ai/chat/stream}) call runs
 * authentication (already done by SecurityConfig before this class ever runs) -&gt; tenant
 * resolution -&gt; rate limit -&gt; a single bounded model call (with tool-calling handled
 * transparently by {@link ChatClient}) -&gt; audit -&gt; response. The queued/running/completed
 * async job shape the canonical spec's demo targets (§14) mention is Sprint 6B's
 * AI-Service-extraction scope (AGENTS.md 6B.3) — disclosed here rather than silently assumed.
 * <p>
 * Conversation memory: the last few completed exchanges for this (user, tenant) are replayed to the model
 * (see {@link AiConversationMemory} for exactly what is and is not kept, and why). This replaces the
 * original Day 14 "no memory of any kind" rule; the system prompt tells the model that earlier turns are
 * context only and that any figure must still come from a tool call in the CURRENT turn.
 * <p>
 * Streaming: when the primary provider is Gemini, the answer is relayed token by token through a
 * {@link StreamListener}. The Ollama fallback is never streamed — its streaming connector is the
 * Windows-JDK-NIO path documented in {@code AiChatConfig} as unusable on this dev machine — so a fallback
 * answer is delivered as a single chunk. A stream that fails AFTER tokens were already sent is not retried
 * through the fallback (that would splice two different answers together); it is reported as unavailable.
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final AiToolService aiToolService;
    private final AiToolInvocationRecorder invocationRecorder;
    private final AiRateLimiter rateLimiter;
    private final AiFacts facts;
    private final AiConversationMemory memory;
    private final String modelName;
    private final String fallbackModelName;
    private final boolean fallbackIsRedundant;
    private final boolean primaryCanStream;
    private final long timeoutSeconds;
    private final long primaryTimeoutSeconds;
    /** A second Gemini model tried before Ollama when the primary fails; null when there is none (Ollama primary, or no different model configured). */
    private ChatClient secondaryChatClient;
    private String secondaryModelName;
    /**
     * After the primary model fails (a timeout, an overload) it is left alone for this long and questions go straight to the next model, instead of every question
     * first waiting out the primary's budget again. 0 (the default for a hand-built service) disables it; Spring sets {@code app.ai.primary-cooldown-seconds} (60).
     * Not applied when the primary IS the last resort (the local model), where there is nothing to hand over to. After the cooldown the primary is tried again.
     */
    private volatile long primaryCooldownSeconds = 0;
    private volatile Instant primarySkippedUntil = Instant.EPOCH;
    /**
     * The most time a whole answer may take, counted from the moment the question arrives and across every model tried. Each stage used to get a fresh budget of its own
     * (Gemini 20 s, then the hosted model up to 60 s, then the local model another 60 s), so a bad day cost a student more than two minutes, longer than the gateway waits
     * for this route, before they were told anything. Now each stage gets the smaller of its own budget and what is left, and a stage with less than {@link #MIN_STAGE_SECONDS}
     * left is not started. 0 (the default for a hand-built service) means no overall limit; Spring sets {@code app.ai.total-timeout-seconds}.
     */
    private volatile long totalTimeoutSeconds = 0;
    /** Below this many seconds left no model is started: none answers a tool-calling question that fast, and starting one only delays the honest "unavailable". */
    static final long MIN_STAGE_SECONDS = 3;
    /**
     * If the primary has not answered, or begun to, after this many seconds, the next model is asked as well and whichever answers first is the answer; the other is
     * cancelled. Without it a stalled primary costs its whole budget (20 s) before the next model is even started. 0 (the default for a hand-built service) disables it;
     * Spring sets {@code app.ai.hedge-after-seconds}. Not used when the primary is the local model, when it was skipped by its cool-down, or once the primary has begun writing
     * to the student (an answer already started is never continued by another model).
     */
    private volatile long hedgeAfterSeconds = 0;
    /** A hosted NVIDIA model tried after the second Gemini model and before Ollama; null when no NVIDIA key is configured. */
    private ChatClient nvidiaChatClient;
    private String nvidiaModelName;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Day 16 §ai-perf: {@code chatClient} is whichever provider {@code spring.ai.model.chat}
     * selected as primary (Gemini by default — see AiChatConfig/application.yml);
     * {@code fallbackChatClient} is ALWAYS the Ollama-backed client (see
     * AiChatConfig#fallbackChatClient), regardless of which is primary. If the primary
     * provider is itself Ollama, {@code fallbackIsRedundant} skips the pointless
     * retry-through-the-same-provider rather than doubling an already-slow local call.
     */
    public AiChatService(ChatClient chatClient, ChatClient fallbackChatClient,
                          AiToolService aiToolService, AiToolInvocationRecorder invocationRecorder,
                          AiRateLimiter rateLimiter, AiFacts facts, AiConversationMemory memory,
                          String activeProvider, String geminiModel, String ollamaModel, long timeoutSeconds) {
        this(chatClient, fallbackChatClient, aiToolService, invocationRecorder, rateLimiter, facts, memory, activeProvider, geminiModel, ollamaModel, timeoutSeconds,
            timeoutSeconds);
    }

    /**
     * {@code primaryTimeoutSeconds} bounds the primary (Gemini) attempt on its own. A hung primary used to cost the whole
     * {@code timeoutSeconds} before the slower local fallback even started (measured: 60s + ~35s = ~98s per answer);
     * the fallback keeps the longer {@code timeoutSeconds} because a local small model genuinely needs it.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AiChatService(ChatClient chatClient, @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                          AiToolService aiToolService, AiToolInvocationRecorder invocationRecorder,
                          AiRateLimiter rateLimiter, AiFacts facts, AiConversationMemory memory,
                          @Value("${spring.ai.model.chat:google-genai}") String activeProvider,
                          @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}") String geminiModel,
                          @Value("${spring.ai.ollama.chat.options.model:llama3.2:3b}") String ollamaModel,
                          @Value("${app.ai.request-timeout-seconds:30}") long timeoutSeconds,
                          @Value("${app.ai.primary-timeout-seconds:40}") long primaryTimeoutSeconds) {
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.aiToolService = aiToolService;
        this.invocationRecorder = invocationRecorder;
        this.rateLimiter = rateLimiter;
        this.facts = facts;
        this.memory = memory;
        this.modelName = "ollama".equals(activeProvider) ? ollamaModel : geminiModel;
        this.fallbackModelName = ollamaModel;
        this.fallbackIsRedundant = "ollama".equals(activeProvider);
        this.primaryCanStream = "google-genai".equals(activeProvider);
        this.timeoutSeconds = timeoutSeconds;
        // The shorter budget exists so a hung remote primary hands over to the local fallback quickly. When the primary IS the local
        // model there is nothing to hand over to, and a small local model doing tool calls genuinely needs the full request timeout.
        this.primaryTimeoutSeconds = this.fallbackIsRedundant ? timeoutSeconds : Math.min(primaryTimeoutSeconds, timeoutSeconds);
    }

    /**
     * Wired by Spring when Gemini is the primary provider and a different fallback model is configured ({@code app.ai.gemini-fallback-model}); not
     * called otherwise. A model equal to the primary is ignored: retrying the model that just failed is what the primary's own retry is for.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void secondaryGemini(@Qualifier("geminiSecondaryChatClient") ChatClient client,
                         @Value("${app.ai.gemini-fallback-model:}") String model,
                         @Value("${spring.ai.google.genai.chat.options.model:}") String primaryModel) {
        if (client != null && model != null && !model.isBlank() && !model.equals(primaryModel)) {
            this.secondaryChatClient = client;
            this.secondaryModelName = model;
        }
    }

    /**
     * Wired by Spring when an NVIDIA API key is configured ({@code app.ai.nvidia.api-key}); not called otherwise. A hosted model answers in seconds, where the local
     * Ollama model takes minutes, so it is tried before Ollama and after the second Gemini model.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void nvidia(@Qualifier("nvidiaChatClient") ChatClient client, @Value("${app.ai.nvidia.model:}") String model) {
        if (client != null && model != null && !model.isBlank()) {
            this.nvidiaChatClient = client;
            this.nvidiaModelName = model;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void primaryCooldown(@Value("${app.ai.primary-cooldown-seconds:60}") long seconds) {
        this.primaryCooldownSeconds = Math.max(0, seconds);
    }

    /** For tests and hand-built services: leave the primary alone for this many seconds after it fails (0: never). */
    public AiChatService withPrimaryCooldown(long seconds) {
        this.primaryCooldownSeconds = Math.max(0, seconds);
        return this;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void totalTimeout(@Value("${app.ai.total-timeout-seconds:0}") long seconds) {
        this.totalTimeoutSeconds = Math.max(0, seconds);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void hedgeAfter(@Value("${app.ai.hedge-after-seconds:0}") long seconds) {
        this.hedgeAfterSeconds = Math.max(0, seconds);
    }

    /** For tests and hand-built services: ask the next model as well when the primary has not answered after this many seconds (0: never). */
    public AiChatService withHedgeAfter(long seconds) {
        this.hedgeAfterSeconds = Math.max(0, seconds);
        return this;
    }

    /** For tests and hand-built services: the most a whole answer may take, across every model tried (0: no overall limit). */
    public AiChatService withTotalTimeout(long seconds) {
        this.totalTimeoutSeconds = Math.max(0, seconds);
        return this;
    }

    /** Who may speak to the student while two models race: nobody yet, the primary once it has written its first word (final), or the fallback once it has won (final). */
    static final class HedgeGate {
        private static final int OPEN = 0;
        private static final int PRIMARY = 1;
        private static final int HEDGE = 2;
        private final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(OPEN);

        boolean isOpen() {
            return state.get() == OPEN;
        }

        /** The fallback has an answer: it may speak only if the primary has not already begun to. */
        boolean winHedge() {
            return state.compareAndSet(OPEN, HEDGE);
        }

        /** The primary is about to write: it may, unless the fallback has already won. */
        boolean commitPrimary() {
            return state.compareAndSet(OPEN, PRIMARY) || state.get() == PRIMARY;
        }

        /** The primary's view of the student's stream: its first word commits it, and once the fallback has won it is silent. */
        StreamListener guard(StreamListener target) {
            return new StreamListener() {
                @Override
                public void onTool(String toolName) {
                    if (state.get() != HEDGE) {
                        target.onTool(toolName);   // only an activity line ("checking your attendance"), not part of the answer
                    }
                }

                @Override
                public void onToken(String text) {
                    if (commitPrimary()) {
                        target.onToken(text);
                    }
                }
            };
        }
    }

    private record Hedged(ChatResult result, boolean hedgeWon) {
    }

    private static Exception asException(Throwable t) {
        return t instanceof Exception e ? e : new IllegalStateException(t);
    }

    /**
     * The primary attempt, with the next model asked as well if the primary is slow. The primary starts alone; if it has neither answered nor begun to write after
     * {@link #hedgeAfterSeconds}, the next model (the second Gemini model, else the hosted one) is started beside it and the first to answer is the answer. Only the
     * winner is delivered to the student and recorded, and the loser is cancelled. A primary that fails before the delay takes the ordinary path (nothing is hedged).
     * Throws like {@link #attempt} when nothing answered; {@code hedgeTried} then names the stage already tried, so the chain does not try it again.
     */
    private Hedged primaryWithHedge(String message, long primaryBudget, StreamListener tracked, List<Message> history, Optional<UUID> tenantId, UUID userId,
                                     UUID correlationId, Instant deadline, String[] hedgeTried) throws Exception {
        boolean stream = primaryCanStream && tracked != null;
        boolean secondaryTarget = secondaryChatClient != null;
        ChatClient hedgeClient = secondaryTarget ? secondaryChatClient : nvidiaChatClient;
        String hedgeModel = secondaryTarget ? secondaryModelName : nvidiaModelName;
        long hedgeStageBudget = secondaryTarget ? primaryTimeoutSeconds : this.timeoutSeconds;
        if (hedgeAfterSeconds <= 0 || fallbackIsRedundant || hedgeClient == null || hedgeAfterSeconds >= primaryBudget) {
            return new Hedged(attempt(chatClient, message, modelName, stream, primaryBudget, tracked, history, tenantId, userId, correlationId), false);
        }

        SecurityContext callerContext = SecurityContextHolder.getContext();
        HedgeGate gate = new HedgeGate();
        StreamListener primaryListener = tracked == null ? null : gate.guard(tracked);
        Future<Raw> primary = executor.submit(() -> modelCall(chatClient, message, stream, primaryBudget, primaryListener, history, callerContext));
        try {
            return new Hedged(conclude(primary.get(hedgeAfterSeconds, TimeUnit.SECONDS), message, modelName, tracked, history, correlationId), false);
        } catch (TimeoutException slow) {
            // not yet: the race begins below
        } catch (ExecutionException failed) {
            throw asException(failed.getCause());   // failed before the delay: the ordinary failure path, nothing was hedged
        } catch (InterruptedException e) {
            primary.cancel(true);
            throw e;
        }

        long hedgeBudget = gate.isOpen() ? budget(hedgeStageBudget, deadline, "hedge") : 0;
        Future<Raw> hedge = null;
        if (hedgeBudget > 0) {
            hedgeTried[0] = secondaryTarget ? "secondary" : "nvidia";
            log.info("AI chat primary provider has not answered in {}s: also asking {} and taking whichever answers first", hedgeAfterSeconds, hedgeModel);
            hedge = executor.submit(() -> modelCall(hedgeClient, message, false, hedgeBudget, null, history, callerContext));
        }
        Throwable primaryError = null;
        boolean primaryDone = false;
        boolean hedgeDone = hedge == null;
        try {
            while (true) {
                if (!primaryDone && primary.isDone()) {
                    try {
                        Raw raw = primary.get();
                        if (hedge != null) {
                            hedge.cancel(true);
                        }
                        return new Hedged(conclude(raw, message, modelName, tracked, history, correlationId), false);
                    } catch (ExecutionException failed) {
                        primaryError = failed.getCause();
                        primaryDone = true;
                    }
                }
                if (hedge != null && !hedgeDone && hedge.isDone()) {
                    try {
                        Raw raw = hedge.get();
                        if (gate.winHedge()) {
                            primary.cancel(true);
                            ChatResult result = conclude(raw, message, hedgeModel, tracked, history, correlationId);
                            audit("AI_CHAT_FALLBACK_USED", correlationId, Map.of("fallbackModel", hedgeModel, "raced", true));
                            return new Hedged(result, true);
                        }
                        hedgeDone = true;   // the primary had already begun to write to the student: it stays, and this answer is not used
                    } catch (ExecutionException failed) {
                        logProviderFailure(asException(failed.getCause()), tenantId, userId, correlationId, "hedge");
                        hedgeDone = true;
                    }
                }
                if (primaryDone && hedgeDone) {
                    throw asException(primaryError);
                }
                if (hedge != null && !hedgeDone && !gate.isOpen()) {
                    hedge.cancel(true);   // the primary has begun to write: it is the answer, and asking the other model any longer only costs
                    hedgeDone = true;
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            primary.cancel(true);
            if (hedge != null) {
                hedge.cancel(true);
            }
            throw e;
        }
    }

    /**
     * What a stage may spend: its own budget, but never more than what is left of the whole answer's. 0 when too little is left for a model to answer, in which case the
     * stage is skipped. With no overall limit it is the stage's own budget, as it always was.
     */
    private long budget(long stageSeconds, Instant deadline, String which) {
        if (deadline == null) {
            return stageSeconds;
        }
        long left = Duration.between(Instant.now(), deadline).toSeconds();
        if (left < MIN_STAGE_SECONDS) {
            log.warn("AI chat {} provider not tried: {}s of the {}s allowed for the whole answer are left", which, Math.max(left, 0), totalTimeoutSeconds);
            return 0;
        }
        return Math.min(stageSeconds, left);
    }

    /** Marks a primary attempt that was not made because the primary failed moments ago: not a failure, so nothing is logged for it. */
    private static final class PrimarySkipped extends RuntimeException {
        PrimarySkipped() {
            super("primary model skipped: it failed recently", null, false, false);
        }
    }

    /** For tests and hand-built services: the hosted NVIDIA model, or null for none. */
    public AiChatService withNvidia(ChatClient client, String model) {
        this.nvidiaChatClient = client;
        this.nvidiaModelName = model;
        return this;
    }

    /** For tests and hand-built services: the second Gemini model, or null for none. */
    public AiChatService withSecondaryGemini(ChatClient client, String model) {
        this.secondaryChatClient = client;
        this.secondaryModelName = model;
        return this;
    }

    public sealed interface ChatResult {
        record Success(String answer, List<String> sources, Instant generatedAt, String model, UUID correlationId) implements ChatResult {
        }

        record InvalidRequest(String reason) implements ChatResult {
        }

        record RateLimited() implements ChatResult {
        }

        record ProviderUnavailable() implements ChatResult {
        }
    }

    /** Receives progress while a chat call runs. Both methods may be called from threads other than the caller's. */
    public interface StreamListener {
        /** A tool is about to run — the raw tool name, e.g. {@code getStudentAttendance}. */
        void onTool(String toolName);

        /** The next piece of the answer text. */
        void onToken(String text);
    }

    /** Empty if the message is acceptable; otherwise the reason it is not. */
    public Optional<String> validate(String message) {
        if (message == null || message.isBlank()) {
            return Optional.of("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return Optional.of("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        return Optional.empty();
    }

    /** Forgets this user's current conversation — the "New conversation" action. */
    public void clearHistory() {
        AiFacts.Caller caller = facts.caller();
        memory.clear(AiConversationMemory.Key.of(caller.userId(), Optional.ofNullable(caller.tenantId())));
    }

    public ChatResult chat(String message) {
        return chat(message, null);
    }

    public ChatResult chat(String message, StreamListener listener) {
        Instant deadline = totalTimeoutSeconds > 0 ? Instant.now().plusSeconds(totalTimeoutSeconds) : null;
        Optional<String> invalid = validate(message);
        if (invalid.isPresent()) {
            return new ChatResult.InvalidRequest(invalid.get());
        }

        AiFacts.Caller caller;
        try {
            caller = facts.caller();
        } catch (AiFacts.Unavailable e) {
            // without knowing who is asking, nothing can be answered, rate limited or audited: say so, do not guess
            log.warn("AI chat refused: the caller could not be identified ({})", e.getMessage());
            return new ChatResult.ProviderUnavailable();
        }
        UUID userId = caller.userId();
        Optional<UUID> tenantId = Optional.ofNullable(caller.tenantId());
        UUID correlationId = UUID.randomUUID();

        if (tenantId.isPresent() && !rateLimiter.tryAcquire(tenantId.get(), userId)) {
            audit("AI_CHAT_RATE_LIMITED", correlationId, Map.of());
            return new ChatResult.RateLimited();
        }

        AiConversationMemory.Key memoryKey = AiConversationMemory.Key.of(userId, tenantId);
        List<Message> history = toMessages(memory.history(memoryKey));

        boolean[] tokensSent = {false};
        StreamListener tracked = listener == null ? null : new StreamListener() {
            @Override
            public void onTool(String toolName) {
                listener.onTool(toolName);
            }

            @Override
            public void onToken(String text) {
                tokensSent[0] = true;
                listener.onToken(text);
            }
        };

        invocationRecorder.drain(); // clear any stale state left on this (pooled) thread by an earlier call
        ChatResult result;
        String[] hedgeTried = {null};   // the stage that was already asked beside the primary, so the chain below does not ask it again
        try {
            if (primaryCooldownSeconds > 0 && !fallbackIsRedundant && Instant.now().isBefore(primarySkippedUntil)) {
                throw new PrimarySkipped();
            }
            Hedged hedged = primaryWithHedge(message,
                deadline == null ? primaryTimeoutSeconds : Math.max(1, Math.min(primaryTimeoutSeconds, Duration.between(Instant.now(), deadline).toSeconds())),
                tracked, history, tenantId, userId, correlationId, deadline, hedgeTried);
            result = hedged.result();
            if (!hedged.hedgeWon()) {
                primarySkippedUntil = Instant.EPOCH;   // it answered: it is healthy again (when the fallback won the race, the primary is neither healthy nor failed: unknown)
            }
        } catch (AuditUnavailableException auditFailure) {
            log.warn("AI answer withheld: it could not be recorded ({})", auditFailure.getMessage());
            return new ChatResult.ProviderUnavailable();
        } catch (Exception primaryFailure) {
            if (!(primaryFailure instanceof PrimarySkipped)) {
                logProviderFailure(primaryFailure, tenantId, userId, correlationId, "primary");
                if (primaryCooldownSeconds > 0 && !fallbackIsRedundant) {
                    primarySkippedUntil = Instant.now().plusSeconds(primaryCooldownSeconds);
                }
            }
            if (tokensSent[0]) {
                return new ChatResult.ProviderUnavailable();   // an answer already started cannot be spliced with another model's
            }
            // First fallback: another Gemini model (the local model is minutes slower than any hosted one).
            ChatResult recovered = null;
            long secondaryBudget = secondaryChatClient == null || "secondary".equals(hedgeTried[0]) ? 0 : budget(primaryTimeoutSeconds, deadline, "secondary");
            if (secondaryBudget > 0) {
                invocationRecorder.drain();
                try {
                    recovered = attempt(secondaryChatClient, message, secondaryModelName, false, secondaryBudget, tracked, history,
                        tenantId, userId, correlationId);
                    audit("AI_CHAT_FALLBACK_USED", correlationId, Map.of("fallbackModel", secondaryModelName));
                } catch (AuditUnavailableException auditFailure) {
                    log.warn("AI answer withheld: it could not be recorded ({})", auditFailure.getMessage());
                    return new ChatResult.ProviderUnavailable();
                } catch (Exception secondaryFailure) {
                    logProviderFailure(secondaryFailure, tenantId, userId, correlationId, "secondary");
                }
            }
            // Second fallback: a hosted NVIDIA model, when a key is configured (still minutes quicker than the local model).
            long nvidiaBudget = recovered != null || nvidiaChatClient == null || "nvidia".equals(hedgeTried[0]) ? 0 : budget(this.timeoutSeconds, deadline, "nvidia");
            if (nvidiaBudget > 0) {
                invocationRecorder.drain();
                try {
                    // the hosted fallback gets the full budget, not the primary's short one (a tool-calling answer is two model calls), but never more than what is left
                    recovered = attempt(nvidiaChatClient, message, nvidiaModelName, false, nvidiaBudget, tracked, history,
                        tenantId, userId, correlationId);
                    audit("AI_CHAT_FALLBACK_USED", correlationId, Map.of("fallbackModel", nvidiaModelName));
                } catch (AuditUnavailableException auditFailure) {
                    log.warn("AI answer withheld: it could not be recorded ({})", auditFailure.getMessage());
                    return new ChatResult.ProviderUnavailable();
                } catch (Exception nvidiaFailure) {
                    logProviderFailure(nvidiaFailure, tenantId, userId, correlationId, "nvidia");
                }
            }
            if (recovered != null) {
                result = recovered;
            } else {
                if (fallbackIsRedundant) {
                    return new ChatResult.ProviderUnavailable();
                }
                long fallbackBudget = budget(this.timeoutSeconds, deadline, "fallback");
                if (fallbackBudget == 0) {
                    return new ChatResult.ProviderUnavailable();
                }
                invocationRecorder.drain();
                try {
                    result = attempt(fallbackChatClient, message, fallbackModelName, false, fallbackBudget, tracked, history,
                        tenantId, userId, correlationId);
                    audit("AI_CHAT_FALLBACK_USED", correlationId, Map.of("fallbackModel", fallbackModelName));
                } catch (AuditUnavailableException auditFailure) {
                    log.warn("AI answer withheld: it could not be recorded ({})", auditFailure.getMessage());
                    return new ChatResult.ProviderUnavailable();
                } catch (Exception fallbackFailure) {
                    logProviderFailure(fallbackFailure, tenantId, userId, correlationId, "fallback");
                    return new ChatResult.ProviderUnavailable();
                }
            }
        }
        if (result instanceof ChatResult.Success success) {
            memory.append(memoryKey, message, success.answer());
        }
        return result;
    }

    /** The advisor could not record what it did, so what it did must not stand. */
    private static final class AuditUnavailableException extends RuntimeException {
        AuditUnavailableException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Diagnostic records (rate limited, timeout, provider error, fallback used): worth keeping, never worth failing a call over. */
    private void audit(String action, UUID correlationId, Map<String, Object> details) {
        try {
            facts.audit(action, correlationId, details);
        } catch (AiFacts.Unavailable e) {
            log.warn("AI audit record {} could not be written: {}", action, e.getMessage());
        }
    }

    private static List<Message> toMessages(List<AiConversationMemory.Turn> turns) {
        List<Message> messages = new ArrayList<>(turns.size() * 2);
        for (AiConversationMemory.Turn turn : turns) {
            messages.add(new UserMessage(turn.user()));
            messages.add(new AssistantMessage(turn.assistant()));
        }
        return messages;
    }

    /**
     * Runs one provider attempt end to end (call, tool sources, success audit); throws on timeout or any
     * provider-side failure for the caller to handle (retry via fallback, or give up).
     * <p>
     * Real bug found live: {@code SecurityContextHolder}'s default strategy is thread-local, and
     * {@link #executor} runs the call (and therefore every tool invocation {@code .tools(aiToolService)} makes
     * synchronously inside it) on a brand-new virtual thread. With no propagation, every tool's "resolve the
     * current principal" path (self-attendance, self-grades, self-deadlines — the primary way this advisor is
     * meant to be used, per each tool's own "omit X for the caller's own record" description) saw an empty
     * security context and reported "not found" regardless of which model answered. Capturing the context on
     * the calling thread and restoring it inside the submitted task is the standard fix for this exact
     * ThreadLocal-across-executor-boundary gap.
     * <p>
     * The streaming branch has the same problem one level deeper: the reactive pipeline may run a tool on a
     * thread this class never created, so there the context is carried by {@link ContextCarryingToolCallback}
     * around each individual tool call instead of by the outer task alone.
     */
    // ChatClient.toolCallbacks(ToolCallback...) is flagged deprecated by Spring AI's current release, but its replacement path is
    // not the same API and this is the call that carries the per-request, security-context-aware tool wrappers.
    /** What a model answered, before anything is delivered or recorded: the text, the tools it used, and whether it was streamed to the listener as it was written. */
    private record Raw(String answer, List<String> sources, boolean streamed) {
    }

    private ChatResult attempt(ChatClient client, String message, String modelForResult, boolean stream,
                                long timeoutSeconds, StreamListener listener, List<Message> history,
                                Optional<UUID> tenantId, UUID userId, UUID correlationId) throws Exception {
        Raw raw = modelCall(client, message, stream, timeoutSeconds, listener, history, SecurityContextHolder.getContext());
        return conclude(raw, message, modelForResult, listener, history, correlationId);
    }

    /**
     * Asks one model and waits for its answer, and nothing else: it delivers nothing to the student and records nothing, so that two models can be asked at once and only the
     * one that wins is delivered and recorded ({@link #conclude}). The caller's security context is passed in, not read here, because this may run on a worker thread.
     */
    // ChatClient.toolCallbacks(ToolCallback...) is flagged deprecated by Spring AI's current release, but its replacement path is
    // not the same API and this is the call that carries the per-request, security-context-aware tool wrappers.
    @SuppressWarnings("removal")
    private Raw modelCall(ChatClient client, String message, boolean stream, long timeoutSeconds, StreamListener listener, List<Message> history,
                          SecurityContext callerContext) throws Exception {
        String answer;
        List<String> sources;
        if (stream) {
            List<String> toolNames = new CopyOnWriteArrayList<>();
            ToolCallback[] tools = wrapTools(callerContext, toolNames, listener);
            Future<String> running = executor.submit(() -> {
                SecurityContextHolder.setContext(callerContext);
                try {
                    StringBuilder text = new StringBuilder();
                    client.prompt()
                        .messages(history)
                        .user(message)
                        .toolCallbacks(tools)
                        .stream()
                        .content()
                        .doOnNext(chunk -> {
                            text.append(chunk);
                            listener.onToken(chunk);
                        })
                        .blockLast(Duration.ofSeconds(timeoutSeconds));
                    return text.toString();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            try {
                answer = running.get(timeoutSeconds + 1, TimeUnit.SECONDS);
            } catch (TimeoutException | InterruptedException e) {
                running.cancel(true);
                throw e;
            }
            sources = List.copyOf(toolNames);
        } else {
            // The tools run (and record their names, in a ThreadLocal) on this worker thread, so the names must be
            // drained HERE — draining on the calling thread afterwards always came back empty and the answer lost its sources.
            record Outcome(String answer, List<String> tools) {
            }
            Future<Outcome> running = executor.submit(() -> {
                    SecurityContextHolder.setContext(callerContext);
                    try {
                        String content = client.prompt()
                            .messages(history)
                            .user(message)
                            .tools(aiToolService)
                            .call()
                            .content();
                        return new Outcome(content, invocationRecorder.drain());
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            Outcome outcome;
            try {
                outcome = running.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException | InterruptedException e) {
                running.cancel(true);   // a stage that ran out of time, or lost a race, stops instead of carrying on in the background and calling the model for an answer nobody will read
                throw e;
            }
            answer = outcome.answer();
            sources = outcome.tools();
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("model returned an empty answer");
        }
        return new Raw(answer, sources, stream);
    }

    /** Delivers an answer that was not streamed (the tools it used, then the text) and records it: the record is part of answering, so if it cannot be written the answer is not given. */
    private ChatResult conclude(Raw raw, String message, String modelForResult, StreamListener listener, List<Message> history, UUID correlationId) {
        if (listener != null && !raw.streamed()) {
            raw.sources().forEach(listener::onTool);
            listener.onToken(raw.answer());
        }
        // The record of what was answered is part of answering: if it cannot be written the answer is not given (fail closed).
        try {
            facts.audit("AI_CHAT_COMPLETED", correlationId,
                Map.of("toolsInvoked", raw.sources(), "messageLength", message.length(), "model", modelForResult,
                    "streamed", raw.streamed(), "priorExchanges", history.size() / 2));
        } catch (AiFacts.Unavailable e) {
            throw new AuditUnavailableException(e);
        }
        return new ChatResult.Success(raw.answer(), raw.sources(), Instant.now(), modelForResult, correlationId);
    }

    private ToolCallback[] wrapTools(SecurityContext callerContext, List<String> toolNames, StreamListener listener) {
        ToolCallback[] raw = ToolCallbacks.from(aiToolService);
        ToolCallback[] wrapped = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            wrapped[i] = new ContextCarryingToolCallback(raw[i], callerContext, toolNames, listener, invocationRecorder);
        }
        return wrapped;
    }

    /**
     * Runs one tool under the caller's security context regardless of which thread the streaming pipeline
     * picked, records the tool name for source attribution, announces it to the listener, and clears the
     * per-thread invocation recorder afterwards (that recorder is thread-local, and on a pooled reactive thread
     * nothing else would ever drain it).
     */
    private static final class ContextCarryingToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final SecurityContext context;
        private final List<String> toolNames;
        private final StreamListener listener;
        private final AiToolInvocationRecorder recorder;

        ContextCarryingToolCallback(ToolCallback delegate, SecurityContext context, List<String> toolNames,
                                     StreamListener listener, AiToolInvocationRecorder recorder) {
            this.delegate = delegate;
            this.context = context;
            this.toolNames = toolNames;
            this.listener = listener;
            this.recorder = recorder;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return run(() -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return run(() -> delegate.call(toolInput, toolContext));
        }

        private String run(Supplier<String> invocation) {
            String name = delegate.getToolDefinition().name();
            if (!toolNames.contains(name)) {
                toolNames.add(name);
            }
            listener.onTool(name);
            SecurityContextHolder.setContext(context);
            try {
                return invocation.get();
            } finally {
                SecurityContextHolder.clearContext();
                recorder.drain();
            }
        }
    }

    /** Never leaks a stack trace or connection details to the caller (no response body reveals this) — logged server-side only, per this class's existing error-handling contract. */
    private void logProviderFailure(Exception e, Optional<UUID> tenantId, UUID userId, UUID correlationId, String which) {
        invocationRecorder.drain();
        if (e instanceof TimeoutException) {
            log.warn("AI chat {} provider timed out (primary budget {}s, overall {}s)", which, primaryTimeoutSeconds, timeoutSeconds);
            audit("AI_CHAT_TIMEOUT", correlationId, Map.of("provider", which));
        } else {
            log.warn("AI chat {} provider call failed", which, e);
            audit("AI_CHAT_PROVIDER_ERROR", correlationId, Map.of("errorType", e.getClass().getSimpleName(), "provider", which));
        }
    }
}
