package com.setu.aiexplain.application;

import com.setu.aiexplain.infrastructure.ratelimit.AiRateLimiter;
import com.setu.identity.TestAuthentication;
import com.setu.shared.aifacts.AiFacts;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL hosted NVIDIA model, asked a real question through the real tools, as the only model (the local one is made to fail, so nothing else can answer).
 * Opt-in because it calls a provider with a key and sends the scenario's example data to it: set {@code NVIDIA_API_KEY} (and optionally {@code NVIDIA_MODEL}) and run
 * {@code ./mvnw test -DexcludedGroups=none -Dtest=AiChatNvidiaLiveTests}. Skipped, not failed, when no key is set. Only seeded example data is ever sent.
 */
@Tag("live-nvidia")
@SpringBootTest(properties = {"app.ai.nvidia.api-key=${NVIDIA_API_KEY:}", "app.ai.nvidia.model=${NVIDIA_MODEL:nvidia/nemotron-3.5-lightning-30b-a3b}"})
class AiChatNvidiaLiveTests {

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
    @Autowired(required = false)
    @Qualifier("nvidiaChatClient")
    ChatClient nvidia;
    @Value("${app.ai.nvidia.model}")
    String model;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private static ChatClient failing() {
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("the local model must not be reached in this test");
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        return ChatClient.builder(broken).build();
    }

    @Test
    void theHostedModelAnswersAStudentsQuestionFromTheirOwnFactsThroughTheRealTools() {
        Assumptions.assumeTrue(nvidia != null, "no NVIDIA_API_KEY set: nothing to call");
        var s = day13Fixtures.provisionEnrolledScenario("NVL1");
        TestAuthentication.authenticateAs(s.student().userId());
        // nvidia is the PRIMARY here, so the answer can only come from it
        AiChatService service = new AiChatService(nvidia, failing(), aiToolService, invocationRecorder, rateLimiter, facts,
            new AiConversationMemory(6, 30, 100), "google-genai", model, "ollama-test", 90L, 90L);

        var result = service.chat("What is my attendance percentage across my courses?");

        assertThat(result).isInstanceOfSatisfying(AiChatService.ChatResult.Success.class, r -> {
            assertThat(r.model()).isEqualTo(model);
            assertThat(r.answer()).isNotBlank();
            assertThat(r.sources()).as("it used a tool for the fact instead of guessing").isNotEmpty();
            assertThat(List.copyOf(r.sources()).toString().toLowerCase()).contains("attendance");
        });
    }
}
