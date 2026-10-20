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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the primary model has just failed, the next questions go straight to the next model instead of each one waiting out the primary's budget again (a slow
 * Gemini made every answer 20 to 40 seconds longer). The primary is tried again after the cooldown, and a cooldown of 0 is exactly the old behaviour.
 */
@SpringBootTest
class AiChatPrimaryCooldownTests {

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

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private static ChatClient model(AtomicInteger calls, String text) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                if (text == null) {
                    throw new IllegalStateException("503 This model is currently experiencing high demand");
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

    private AiChatService service(ChatClient primary, long cooldownSeconds) {
        return new AiChatService(primary, model(new AtomicInteger(), "ollama answer"), aiToolService, invocationRecorder, rateLimiter, facts,
            new AiConversationMemory(6, 30, 100), "google-genai", "gemini-primary", "ollama-test", 30L, 30L)
            .withNvidia(model(nvidiaCalls, "nvidia answer"), "meta/llama-test")
            .withPrimaryCooldown(cooldownSeconds);
    }

    private String ask(AiChatService service, java.util.UUID scenarioUser) {
        TestAuthentication.authenticateAs(scenarioUser);
        var result = service.chat("How am I doing?");
        assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
        return ((AiChatService.ChatResult.Success) result).model();
    }

    @Test
    void afterThePrimaryFailsTheNextQuestionsDoNotAskItAgainUntilTheCooldownEnds() {
        var s = day13Fixtures.provisionEnrolledScenario("CDN1");
        AiChatService service = service(model(primaryCalls, null), 60);

        assertThat(ask(service, s.student().userId())).isEqualTo("meta/llama-test");
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(ask(service, s.student().userId())).isEqualTo("meta/llama-test");
        assertThat(ask(service, s.student().userId())).isEqualTo("meta/llama-test");

        assertThat(primaryCalls.get()).as("asked once, then left alone for the cooldown").isEqualTo(1);
        assertThat(nvidiaCalls.get()).isEqualTo(3);
    }

    @Test
    void aCooldownOfZeroIsTheOldBehaviourAndAsksThePrimaryEveryTime() {
        var s = day13Fixtures.provisionEnrolledScenario("CDN2");
        AiChatService service = service(model(primaryCalls, null), 0);

        ask(service, s.student().userId());
        ask(service, s.student().userId());

        assertThat(primaryCalls.get()).isEqualTo(2);
    }

    @Test
    void aPrimaryThatAnswersIsNeverSkipped() {
        var s = day13Fixtures.provisionEnrolledScenario("CDN3");
        AiChatService service = service(model(primaryCalls, "primary answer"), 60);

        assertThat(ask(service, s.student().userId())).isEqualTo("gemini-primary");
        assertThat(ask(service, s.student().userId())).isEqualTo("gemini-primary");

        assertThat(primaryCalls.get()).isEqualTo(2);
        assertThat(nvidiaCalls.get()).isZero();
    }

    @Test
    void theCooldownEndsAndThePrimaryIsTriedAgain() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("CDN4");
        AiChatService service = service(model(primaryCalls, null), 1);

        ask(service, s.student().userId());
        Thread.sleep(1200);
        ask(service, s.student().userId());

        assertThat(primaryCalls.get()).as("tried again once the cooldown had passed").isEqualTo(2);
    }
}
