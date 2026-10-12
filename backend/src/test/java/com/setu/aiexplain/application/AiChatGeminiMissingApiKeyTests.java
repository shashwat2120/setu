package com.setu.aiexplain.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 16 §ai-perf TEST: "missing API key" — Gemini selected as the primary provider with a genuinely blank
 * {@code spring.ai.google.genai.api-key}. Spring AI 2.x refuses to build its Google GenAI client without
 * credentials, which used to abort the whole application at startup; {@code AiProviderSafetyPostProcessor} now
 * switches the primary provider to Ollama in that case, so the app must START and still answer (or return a
 * controlled {@code ProviderUnavailable}) — never crash, never leak a stack trace. Runs in its own Spring context so
 * the provider override never affects any other AI test.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=google-genai", "spring.ai.google.genai.api-key="})
class AiChatGeminiMissingApiKeyTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    AiChatService aiChatService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void blankApiKeyDoesNotCrashTheApplication_realFailureHandledSafely() {
        var s = day13Fixtures.provisionEnrolledScenario("AIGMK1");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            var result = aiChatService.chat("Hello, what can you help me with?");

            // Either a controlled ProviderUnavailable (both Gemini auth and fallback failed) or a real Success
            // via the Ollama fallback — never an unhandled exception/500, and never a leaked stack trace either way.
            assertThat(result).isInstanceOfAny(
                AiChatService.ChatResult.Success.class, AiChatService.ChatResult.ProviderUnavailable.class);
        } finally {
            TestAuthentication.clear();
        }
    }
}
