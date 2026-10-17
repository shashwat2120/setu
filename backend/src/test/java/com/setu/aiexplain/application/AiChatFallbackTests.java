package com.setu.aiexplain.application;

import com.setu.aiexplain.infrastructure.ratelimit.AiRateLimiter;
import com.setu.identity.TestAuthentication;
import com.setu.shared.aifacts.AiFacts;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 16 §ai-perf: {@link AiChatService}'s NEW runtime fallback path (primary provider fails
 * or times out -&gt; retry once via the always-available Ollama {@code fallbackChatClient} —
 * see AiChatConfig's javadoc). Deliberately real, not mocked, matching this codebase's own
 * established discipline (see AiChatProviderUnavailableTests): "broken" providers are real
 * {@link OllamaChatModel} instances genuinely pointed at unreachable addresses, and the
 * "working" side is the real local Ollama every other AI test in this module already relies
 * on being installed and running.
 */
@SuppressWarnings("removal") // Spring AI: Builder#toolCallingManager and ChatModel#getDefaultOptions, see AiChatConfig
@SpringBootTest
class AiChatFallbackTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    AiToolService aiToolService;
    @Autowired
    AiToolInvocationRecorder invocationRecorder;
    @Autowired
    AiRateLimiter rateLimiter;
    @Autowired
    AiFacts facts;
    @Autowired
    RestClient.Builder restClientBuilder;
    @Autowired
    ToolCallingManager toolCallingManager;
    @Autowired
    @Qualifier("fallbackChatClient")
    ChatClient realOllamaClient; // the app's own always-available Ollama client — genuinely working
    @Value("${spring.ai.ollama.chat.options.model:llama3.2:3b}")
    String ollamaModel;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    /** A real ChatClient genuinely pointed at a port nothing listens on — same technique as AiChatProviderUnavailableTests, not a mock. */
    private ChatClient brokenClient() {
        // OllamaApi.builder() otherwise creates a reactive WebClient whose default JDK HttpClient opens a NIO Selector right
        // away, which fails on this Windows dev machine (see AiChatConfig). These calls are synchronous (RestClient) and
        // never stream, so a connector that is never invoked is all the WebClient needs — the same approach AiChatConfig uses.
        WebClient.Builder neverUsedWebClient = WebClient.builder()
            .clientConnector((method, uri, callback) -> Mono.error(new IllegalStateException("streaming is not used by these tests")));
        OllamaApi brokenApi = OllamaApi.builder().baseUrl("http://localhost:1").restClientBuilder(restClientBuilder)
            .webClientBuilder(neverUsedWebClient).build();
        OllamaChatModel brokenModel = OllamaChatModel.builder()
            .ollamaApi(brokenApi)
            .toolCallingManager(toolCallingManager)
            .retryTemplate(org.springframework.ai.retry.RetryUtils.SHORT_RETRY_TEMPLATE)
            .options(OllamaChatOptions.builder().model(ollamaModel).build())
            .build();
        return ChatClient.builder(brokenModel).build();
    }

    /** The app's own 30s request timeout is too tight for a real local Ollama doing tool calls on a CPU-only machine; the tests that need a real Ollama answer allow far longer. They check WHICH provider answered, not how fast. */
    private static final long REAL_OLLAMA_TIMEOUT_SECONDS = 300L;

    private AiChatService serviceWith(ChatClient primary, ChatClient fallback, String activeProvider) {
        return serviceWith(primary, fallback, activeProvider, 30L);
    }

    private AiChatService serviceWith(ChatClient primary, ChatClient fallback, String activeProvider, long timeoutSeconds) {
        return new AiChatService(primary, fallback, aiToolService, invocationRecorder, rateLimiter, facts, new AiConversationMemory(6, 30, 100), activeProvider,
            "gemini-2.5-flash", ollamaModel, timeoutSeconds);
    }

    /** A scripted model: waits {@code delayMillis}, then answers {@code text}. Lets a test control exactly how slow a provider is. */
    private static ChatClient scriptedClient(long delayMillis, String text) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", e);
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        return ChatClient.builder(model).build();
    }

    @Test
    void aHungPrimaryIsAbandonedAfterItsOwnShortBudgetNotTheWholeRequestTimeout() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB4");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            // The primary stalls for 8s; its own budget is 1s while the overall request budget is 30s. Before the split the
            // caller waited the full overall budget for the primary before the fallback even started.
            var service = new AiChatService(scriptedClient(8_000, "too late"), scriptedClient(0, "fallback answer"),
                aiToolService, invocationRecorder, rateLimiter, facts,
                new AiConversationMemory(6, 30, 100), "google-genai", "gemini-test", ollamaModel, 30L, 1L);

            long started = System.nanoTime();
            var result = service.chat("Hello, what can you help me with?");
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
            var success = (AiChatService.ChatResult.Success) result;
            assertThat(success.answer()).isEqualTo("fallback answer");
            assertThat(success.model()).isEqualTo(ollamaModel);
            assertThat(elapsedMillis).as("answered via fallback well before the primary's 8s stall ends").isLessThan(6_000);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void whenThePrimaryIsOllamaItselfThePrimaryBudgetDoesNotCutItOff() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB6");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            // There is no fallback to hand over to, so the 1s primary budget must not apply: a 2.5s answer within the 30s request
            // timeout still succeeds. (Regression: a 25s cap made every slow-but-working local Ollama answer fail.)
            var service = new AiChatService(scriptedClient(2_500, "local answer"), scriptedClient(0, "unused fallback"),
                aiToolService, invocationRecorder, rateLimiter, facts,
                new AiConversationMemory(6, 30, 100), "ollama", "gemini-test", ollamaModel, 30L, 1L);

            var result = service.chat("Hello");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
            assertThat(((AiChatService.ChatResult.Success) result).answer()).isEqualTo("local answer");
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void aSlowButAnsweringPrimaryWithinItsBudgetIsStillUsed() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB5");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            var service = new AiChatService(scriptedClient(300, "primary answer"), scriptedClient(0, "fallback answer"),
                aiToolService, invocationRecorder, rateLimiter, facts,
                new AiConversationMemory(6, 30, 100), "google-genai", "gemini-test", ollamaModel, 30L, 5L);

            var result = service.chat("Hello");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
            var success = (AiChatService.ChatResult.Success) result;
            assertThat(success.answer()).isEqualTo("primary answer");
            assertThat(success.model()).isEqualTo("gemini-test");
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("live-ollama")   // calls the real local model: minutes, not seconds; run on purpose (see pom.xml)
    void primaryFailureFallsBackToOllamaAndStillSucceeds() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB1");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            // "google-genai" as the label proves this is a genuine primary-provider failure driving the
            // fallback, not the redundant-skip path (which only triggers when activeProvider is "ollama").
            var service = serviceWith(brokenClient(), realOllamaClient, "google-genai", REAL_OLLAMA_TIMEOUT_SECONDS);

            var result = service.chat("Hello, what can you help me with?");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
            assertThat(((AiChatService.ChatResult.Success) result).model()).isEqualTo(ollamaModel);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void bothProvidersUnreachableReturnsControlledErrorNeverAStackTrace() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB2");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            var service = serviceWith(brokenClient(), brokenClient(), "google-genai");

            var result = service.chat("What is my attendance?");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.ProviderUnavailable.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void primaryIsOllamaItself_skipsThePointlessRedundantFallbackRetry() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB3");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            // activeProvider = "ollama" marks the fallback as redundant (see AiChatService's constructor
            // javadoc) — a broken primary here must NOT attempt the (also broken) fallback at all; it should
            // fail immediately rather than waste a second connection attempt against the same kind of provider.
            var service = serviceWith(brokenClient(), brokenClient(), "ollama");

            var result = service.chat("What is my attendance?");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.ProviderUnavailable.class);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("live-ollama")   // calls the real local model: minutes, not seconds; run on purpose (see pom.xml)
    void primarySucceedsDoesNotTouchFallbackAtAll() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB4");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            // Fallback is deliberately ALSO broken here — if the primary (real, working) path is ever
            // incorrectly bypassed in favor of the fallback, this would fail instead of silently passing.
            var service = serviceWith(realOllamaClient, brokenClient(), "ollama", REAL_OLLAMA_TIMEOUT_SECONDS);

            var result = service.chat("Hello, what can you help me with?");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
            assertThat(((AiChatService.ChatResult.Success) result).model()).isEqualTo(ollamaModel);
        } finally {
            TestAuthentication.clear();
        }
    }
}
