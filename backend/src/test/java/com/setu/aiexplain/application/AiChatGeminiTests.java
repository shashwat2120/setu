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
 */
@SpringBootTest(properties = "spring.ai.model.chat=google-genai")
class AiChatGeminiTests {

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
