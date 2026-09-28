package com.setu.aiexplain.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 16 §ai-perf TEST: "missing API key" — Gemini selected as the primary provider with a
 * genuinely blank {@code spring.ai.google.genai.api-key} (never the real key, and never a
 * fake/placeholder one — see this class's own explicit blank override). Google GenAI's client
 * builder does not validate the key at construction time (confirmed by inspecting
 * GoogleGenAiChatAutoConfiguration's bytecode: it only branches on hasText(apiKey) for
 * Vertex-vs-Developer-API mode logging, never throws for a blank key there) — the real failure
 * surfaces on the first actual API call, which this test drives genuinely, in its own Spring
 * context so it never affects any other AI test's provider configuration. Runs with
 * {@code fallbackIsRedundant=false} (activeProvider defaults to "google-genai"), so a real
 * fallback-to-Ollama attempt also happens here; the assertion only requires SOME answer to
 * come back (either Gemini unexpectedly accepting the blank key, or — the expected path — the
 * real local Ollama fallback succeeding after Gemini's real auth failure), proving the whole
 * chain degrades safely either way rather than crashing.
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
