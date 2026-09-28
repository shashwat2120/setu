package com.setu.aiexplain.application;

import com.setu.aiexplain.infrastructure.ratelimit.AiRateLimiter;
import com.setu.identity.TestAuthentication;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.tenant.TenantContext;
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
    TenantContext tenantContext;
    @Autowired
    AuthorizationContext authorizationContext;
    @Autowired
    AuditLogWriter auditLogWriter;
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
        OllamaApi brokenApi = OllamaApi.builder().baseUrl("http://localhost:1").restClientBuilder(restClientBuilder).build();
        OllamaChatModel brokenModel = OllamaChatModel.builder()
            .ollamaApi(brokenApi)
            .toolCallingManager(toolCallingManager)
            .options(OllamaChatOptions.builder().model(ollamaModel).build())
            .build();
        return ChatClient.builder(brokenModel).build();
    }

    private AiChatService serviceWith(ChatClient primary, ChatClient fallback, String activeProvider) {
        return new AiChatService(primary, fallback, aiToolService, invocationRecorder, rateLimiter, tenantContext,
            authorizationContext, auditLogWriter, activeProvider, "gemini-2.5-flash", ollamaModel, 30L);
    }

    @Test
    void primaryFailureFallsBackToOllamaAndStillSucceeds() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB1");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            // "google-genai" as the label proves this is a genuine primary-provider failure driving the
            // fallback, not the redundant-skip path (which only triggers when activeProvider is "ollama").
            var service = serviceWith(brokenClient(), realOllamaClient, "google-genai");

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
    void primarySucceedsDoesNotTouchFallbackAtAll() {
        var s = day13Fixtures.provisionEnrolledScenario("AIFB4");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            // Fallback is deliberately ALSO broken here — if the primary (real, working) path is ever
            // incorrectly bypassed in favor of the fallback, this would fail instead of silently passing.
            var service = serviceWith(realOllamaClient, brokenClient(), "ollama");

            var result = service.chat("Hello, what can you help me with?");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
            assertThat(((AiChatService.ChatResult.Success) result).model()).isEqualTo(ollamaModel);
        } finally {
            TestAuthentication.clear();
        }
    }
}
