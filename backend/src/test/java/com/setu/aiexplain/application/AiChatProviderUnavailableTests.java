package com.setu.aiexplain.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 14 §7/§18/§21 TEST 13: "Ollama unavailable -&gt; safe controlled
 * error." Genuinely points {@code spring.ai.ollama.base-url} at a port
 * nothing listens on — a real failed connection, not a mock standing in
 * for one (this project's own established discipline, and the task's own
 * instruction not to fake a successful provider integration). Runs in its
 * own Spring context (different property set than every other test
 * class) so it never affects the real-Ollama tests elsewhere.
 */
// Gemini is the default primary now, so "no provider answers" needs the local model forced as primary AND unreachable
@SpringBootTest(properties = {"spring.ai.model.chat=ollama", "spring.ai.ollama.base-url=http://localhost:1"})
class AiChatProviderUnavailableTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    AiChatService aiChatService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void unreachableProvider_returnsControlledErrorNeverAStackTrace() {
        var s = day13Fixtures.provisionEnrolledScenario("AICU1");
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            var result = aiChatService.chat("What is my attendance?");
            assertThat(result).isInstanceOf(AiChatService.ChatResult.ProviderUnavailable.class);
        } finally {
            TestAuthentication.clear();
        }
    }
}
