package com.setu.aiexplain.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 16 §ai-perf: real, non-mocked tests against the Google GenAI (Gemini Developer API)
 * provider — {@code spring.ai.model.chat=google-genai} overrides this test class onto Gemini;
 * every other test in this codebase keeps defaulting to Ollama (application.yml's bare
 * default), so only this class (and {@link AiChatGeminiMissingApiKeyTests}) ever requires a
 * live {@code GEMINI_API_KEY}.
 * <p>
 * {@link #answersARealQuestionThroughGemini} requires a real {@code GEMINI_API_KEY}
 * environment variable to be set when this class runs — it makes a genuine API call, per this
 * codebase's own established discipline of never mocking a provider integration (see
 * AiChatIntegrationTests, the equivalent real-Ollama suite).
 * <p>
 * Gemini is the default provider for everything now, so this runs with the rest of the suite; it is skipped (not failed) when no
 * {@code GEMINI_API_KEY} is configured. If the key's daily quota is exhausted the application correctly falls back to Ollama and this
 * test's {@code contains("gemini")} check fails for a reason that has nothing to do with the code: wait for the quota to reset.
 */
@SpringBootTest(properties = "spring.ai.model.chat=google-genai")
class AiChatGeminiTests {

    @Autowired
    org.springframework.core.env.Environment environment;
    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    AiChatService aiChatService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void answersARealQuestionThroughGemini() {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.springframework.util.StringUtils.hasText(environment.getProperty("spring.ai.google.genai.api-key")),
            "no GEMINI_API_KEY configured");
        var s = day13Fixtures.provisionEnrolledScenario("AIGM1");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            var result = aiChatService.chat("Hello, what can you help me with?");

            assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
            var success = (AiChatService.ChatResult.Success) result;
            assertThat(success.answer()).isNotBlank();
            assertThat(success.model()).contains("gemini");
            assertThat(success.correlationId()).isNotNull();
        } finally {
            TestAuthentication.clear();
        }
    }
}
