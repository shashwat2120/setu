package com.setu.aiexplain.application;

import com.setu.aiexplain.infrastructure.ratelimit.AiRateLimiter;
import com.setu.identity.TestAuthentication;
import com.setu.shared.aifacts.AiFacts;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the primary model has neither answered nor begun to write after the hedge delay, the next model is asked as well and the first to answer is the answer. These tests
 * use models that really take time (and one that streams), so the clock is the real one. What they hold: a stalled primary is replaced, a slow primary that does answer
 * still wins, nothing is asked when the primary is prompt or fails at once, only the winner is delivered and recorded, the loser is cancelled, a stage that was already
 * raced is not asked again by the chain, and an answer already begun on the student's screen is never continued or replaced by another model's.
 */
@SpringBootTest
class AiChatHedgeTests {

    @Autowired
    AiToolService aiToolService;
    @Autowired
    AiToolInvocationRecorder invocationRecorder;
    @Autowired
    AiRateLimiter rateLimiter;
    @Autowired
    AiFacts facts;
    @Autowired
    Day13TestFixtures day13Fixtures;

    private final AtomicInteger primaryCalls = new AtomicInteger();
    private final AtomicInteger nvidiaCalls = new AtomicInteger();
    private final AtomicInteger ollamaCalls = new AtomicInteger();

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    /** Takes {@code delayMillis} and then answers {@code text}, or fails if {@code text} is null; {@code stopped} is counted down if it is interrupted while it waits. */
    private static ChatClient model(AtomicInteger calls, long delayMillis, String text, CountDownLatch stopped) {
        return ChatClient.builder(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    if (stopped != null) {
                        stopped.countDown();
                    }
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("stopped");
                }
                if (text == null) {
                    throw new IllegalStateException("503 This model is currently experiencing high demand");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        }).build();
    }

    /** A model that streams its answer word by word, the first one after {@code firstWordMillis}. */
    private static ChatClient streamingModel(AtomicInteger calls, long firstWordMillis, List<String> words) {
        return ChatClient.builder(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("this one only streams");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                calls.incrementAndGet();
                return Flux.fromIterable(words).delaySubscription(Duration.ofMillis(firstWordMillis))
                    .map(word -> new ChatResponse(List.of(new Generation(new AssistantMessage(word)))));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        }).build();
    }

    private AiChatService service(ChatClient primary, long primaryBudgetSeconds, ChatClient nvidia, long hedgeAfterSeconds) {
        return new AiChatService(primary, model(ollamaCalls, 0, "ollama answer", null), aiToolService, invocationRecorder, rateLimiter, facts,
            new AiConversationMemory(6, 30, 100), "google-genai", "gemini-primary", "ollama-test", 30L, primaryBudgetSeconds)
            .withNvidia(nvidia, "nvidia-test")
            .withPrimaryCooldown(0)
            .withHedgeAfter(hedgeAfterSeconds);
    }

    private record Timed(AiChatService.ChatResult result, Duration took, List<String> tokens) {
        String model() {
            return ((AiChatService.ChatResult.Success) result).model();
        }

        String answer() {
            return ((AiChatService.ChatResult.Success) result).answer();
        }
    }

    private Timed ask(AiChatService service, UUID user, boolean streaming) {
        TestAuthentication.authenticateAs(user);
        List<String> tokens = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        var result = streaming
            ? service.chat("How am I doing?", new AiChatService.StreamListener() {
                @Override
                public void onTool(String toolName) {
                }

                @Override
                public void onToken(String text) {
                    tokens.add(text);
                }
            })
            : service.chat("How am I doing?");
        return new Timed(result, Duration.ofNanos(System.nanoTime() - start), tokens);
    }

    @Test
    void aStalledPrimaryIsRacedAndTheFallbackWinsLongBeforeThePrimaryWouldHaveGivenUp() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("HDG1");
        var primaryStopped = new CountDownLatch(1);
        var service = service(model(primaryCalls, 30_000, "late", primaryStopped), 10, model(nvidiaCalls, 1_500, "hosted answer", null), 1);

        var timed = ask(service, s.student().userId(), false);

        assertThat(timed.result()).isInstanceOf(AiChatService.ChatResult.Success.class);
        assertThat(timed.model()).isEqualTo("nvidia-test");
        assertThat(timed.took()).as("a second to start the race, a second and a half for the hosted model; not the primary's 10 s first").isBetween(Duration.ofMillis(2000), Duration.ofMillis(4500));
        assertThat(primaryStopped.await(3, TimeUnit.SECONDS)).as("the primary that lost was cancelled").isTrue();
    }

    @Test
    void aSlowPrimaryThatDoesAnswerStillWinsAndTheRacedFallbackIsCancelled() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("HDG2");
        var nvidiaStopped = new CountDownLatch(1);
        var service = service(model(primaryCalls, 2_000, "primary answer", null), 10, model(nvidiaCalls, 30_000, "late", nvidiaStopped), 1);

        var timed = ask(service, s.student().userId(), false);

        assertThat(timed.model()).isEqualTo("gemini-primary");
        assertThat(timed.answer()).isEqualTo("primary answer");
        assertThat(timed.took()).isBetween(Duration.ofMillis(1800), Duration.ofMillis(4000));
        assertThat(nvidiaCalls).as("it was asked, because the primary was slow").hasValue(1);
        assertThat(nvidiaStopped.await(3, TimeUnit.SECONDS)).as("the fallback that lost was cancelled").isTrue();
    }

    @Test
    void aPrimaryThatAnswersBeforeTheDelayNeverStartsTheFallback() {
        var s = day13Fixtures.provisionEnrolledScenario("HDG3");
        var service = service(model(primaryCalls, 100, "primary answer", null), 10, model(nvidiaCalls, 0, "hosted answer", null), 2);

        var timed = ask(service, s.student().userId(), false);

        assertThat(timed.model()).isEqualTo("gemini-primary");
        assertThat(nvidiaCalls).as("nothing to hedge").hasValue(0);
    }

    @Test
    void aPrimaryThatFailsBeforeTheDelayTakesTheOrdinaryPathAndTheFallbackIsAskedOnce() {
        var s = day13Fixtures.provisionEnrolledScenario("HDG4");
        var service = service(model(primaryCalls, 0, null, null), 10, model(nvidiaCalls, 0, "hosted answer", null), 2);

        var timed = ask(service, s.student().userId(), false);

        assertThat(timed.model()).isEqualTo("nvidia-test");
        assertThat(nvidiaCalls).hasValue(1);
    }

    @Test
    void ifTheRacedFallbackFailsThePrimaryStillAnswers() {
        var s = day13Fixtures.provisionEnrolledScenario("HDG5");
        var service = service(model(primaryCalls, 2_500, "primary answer", null), 10, model(nvidiaCalls, 0, null, null), 1);

        var timed = ask(service, s.student().userId(), false);

        assertThat(timed.model()).isEqualTo("gemini-primary");
        assertThat(nvidiaCalls).hasValue(1);
    }

    @Test
    void ifBothFailTheChainDoesNotAskTheStageThatWasAlreadyRacedAgainAndTheLastResortAnswers() {
        var s = day13Fixtures.provisionEnrolledScenario("HDG6");
        var service = service(model(primaryCalls, 1_800, null, null), 10, model(nvidiaCalls, 1_200, null, null), 1);

        var timed = ask(service, s.student().userId(), false);

        assertThat(timed.model()).isEqualTo("ollama-test");
        assertThat(nvidiaCalls).as("raced once, not again as the next stage").hasValue(1);
        assertThat(ollamaCalls).hasValue(1);
    }

    @Test
    void withTheHedgeSwitchedOffItIsTheOldSequentialBehaviour() {
        var s = day13Fixtures.provisionEnrolledScenario("HDG7");
        var service = service(model(primaryCalls, 30_000, "late", null), 2, model(nvidiaCalls, 0, "hosted answer", null), 0);

        var timed = ask(service, s.student().userId(), false);

        assertThat(timed.model()).isEqualTo("nvidia-test");
        assertThat(timed.took()).as("the primary's whole 2 s budget first").isGreaterThanOrEqualTo(Duration.ofMillis(1900));
    }

    @Test
    void aPrimaryThatHasBegunToWriteToTheStudentIsNeverContinuedOrReplacedByAnotherModel() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("HDG8");
        var nvidiaStopped = new CountDownLatch(1);
        // its first word after 1.5 s, past the 1 s hedge delay: the fallback starts, then the primary begins to write, and the fallback (which would answer at 2.5 s) must lose
        var service = service(streamingModel(primaryCalls, 1_500, List.of("Hello ", "from ", "the primary.")), 10, model(nvidiaCalls, 2_500, "hosted answer", nvidiaStopped), 1);

        var timed = ask(service, s.student().userId(), true);

        assertThat(timed.model()).isEqualTo("gemini-primary");
        assertThat(String.join("", timed.tokens())).as("exactly what the primary wrote, nothing of the hosted model's").isEqualTo("Hello from the primary.");
        assertThat(nvidiaStopped.await(3, TimeUnit.SECONDS)).as("the hosted model was cancelled as soon as the primary committed").isTrue();
    }

    @Test
    void aStreamingPrimaryThatStallsIsReplacedAndTheStudentSeesOnlyTheWinnersAnswer() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("HDG9");
        var service = service(streamingModel(primaryCalls, 8_000, List.of("Late ", "words.")), 20, model(nvidiaCalls, 1_000, "hosted answer", null), 1);

        var timed = ask(service, s.student().userId(), true);

        assertThat(timed.model()).isEqualTo("nvidia-test");
        assertThat(timed.tokens()).as("the winner's answer once, and none of the primary's words").containsExactly("hosted answer");
        Thread.sleep(500);
        assertThat(timed.tokens()).containsExactly("hosted answer");
    }

    @Test
    void theGateLetsExactlyOneModelSpeak() {
        var spoken = new CopyOnWriteArrayList<String>();
        var target = new AiChatService.StreamListener() {
            @Override
            public void onTool(String toolName) {
                spoken.add("tool:" + toolName);
            }

            @Override
            public void onToken(String text) {
                spoken.add(text);
            }
        };

        // the primary writes first: it is committed and the fallback can no longer win
        var primaryFirst = new AiChatService.HedgeGate();
        var guarded = primaryFirst.guard(target);
        guarded.onTool("lookup");
        guarded.onToken("a");
        guarded.onToken("b");
        assertThat(primaryFirst.winHedge()).as("the primary has begun: the fallback may not replace it").isFalse();
        assertThat(primaryFirst.isOpen()).isFalse();
        assertThat(spoken).containsExactly("tool:lookup", "a", "b");

        // the fallback wins first: the primary is silent from then on, even about its tools
        spoken.clear();
        var hedgeFirst = new AiChatService.HedgeGate();
        var muted = hedgeFirst.guard(target);
        assertThat(hedgeFirst.winHedge()).isTrue();
        muted.onToken("too late");
        muted.onTool("too late too");
        assertThat(hedgeFirst.commitPrimary()).isFalse();
        assertThat(spoken).isEmpty();
        assertThat(hedgeFirst.winHedge()).as("it can only be won once").isFalse();
    }
}
