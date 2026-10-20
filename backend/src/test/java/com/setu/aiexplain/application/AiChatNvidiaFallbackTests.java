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
 * The hosted NVIDIA model in the order models are tried: the primary Gemini model, then the second Gemini model, then NVIDIA, and only then the local
 * Ollama model (which is minutes slower than any hosted one). Scripted models, so this checks the order and what each answer says gave it, not a provider.
 */
@SpringBootTest
class AiChatNvidiaFallbackTests {

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
    private final AtomicInteger secondaryCalls = new AtomicInteger();
    private final AtomicInteger nvidiaCalls = new AtomicInteger();
    private final AtomicInteger ollamaCalls = new AtomicInteger();

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    /** A model that answers {@code text}, or fails like an overloaded provider ({@code text == null}), counting its calls. */
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

    private AiChatService service(ChatClient primary, ChatClient secondary, ChatClient nvidia, ChatClient ollama) {
        AiChatService service = new AiChatService(primary, ollama, aiToolService, invocationRecorder, rateLimiter, facts,
            new AiConversationMemory(6, 30, 100), "google-genai", "gemini-primary", "ollama-test", 30L, 30L);
        if (secondary != null) {
            service.withSecondaryGemini(secondary, "gemini-secondary");
        }
        return nvidia == null ? service : service.withNvidia(nvidia, "meta/llama-test");
    }

    private AiChatService.ChatResult ask(AiChatService service, String prefix) {
        var s = day13Fixtures.provisionEnrolledScenario(prefix);
        TestAuthentication.authenticateAs(s.student().userId());
        return service.chat("How am I doing?");
    }

    @Test
    void whenBothGeminiModelsFailNvidiaAnswersAndTheLocalModelIsNeverAsked() {
        var result = ask(service(model(primaryCalls, null), model(secondaryCalls, null), model(nvidiaCalls, "nvidia answer"), model(ollamaCalls, "ollama answer")), "NVF1");

        assertThat(result).isInstanceOfSatisfying(AiChatService.ChatResult.Success.class, r -> {
            assertThat(r.answer()).isEqualTo("nvidia answer");
            assertThat(r.model()).as("the answer says which model gave it").isEqualTo("meta/llama-test");
        });
        assertThat(primaryCalls.get()).isEqualTo(1);
        assertThat(secondaryCalls.get()).isEqualTo(1);
        assertThat(nvidiaCalls.get()).isEqualTo(1);
        assertThat(ollamaCalls.get()).isZero();
    }

    @Test
    void anAnsweringGeminiModelIsNeverPassedOverForNvidia() {
        var viaPrimary = ask(service(model(primaryCalls, "primary answer"), model(secondaryCalls, "secondary answer"), model(nvidiaCalls, "nvidia answer"), model(ollamaCalls, "o")), "NVF2A");
        assertThat(viaPrimary).isInstanceOfSatisfying(AiChatService.ChatResult.Success.class, r -> assertThat(r.model()).isEqualTo("gemini-primary"));

        var viaSecondary = ask(service(model(primaryCalls, null), model(secondaryCalls, "secondary answer"), model(nvidiaCalls, "nvidia answer"), model(ollamaCalls, "o")), "NVF2B");
        assertThat(viaSecondary).isInstanceOfSatisfying(AiChatService.ChatResult.Success.class, r -> assertThat(r.model()).isEqualTo("gemini-secondary"));

        assertThat(nvidiaCalls.get()).isZero();
        assertThat(ollamaCalls.get()).isZero();
    }

    @Test
    void whenNvidiaFailsToo_theLocalModelAnswersLast() {
        var result = ask(service(model(primaryCalls, null), model(secondaryCalls, null), model(nvidiaCalls, null), model(ollamaCalls, "ollama answer")), "NVF3");

        assertThat(result).isInstanceOfSatisfying(AiChatService.ChatResult.Success.class, r -> {
            assertThat(r.answer()).isEqualTo("ollama answer");
            assertThat(r.model()).isEqualTo("ollama-test");
        });
        assertThat(nvidiaCalls.get()).isEqualTo(1);
        assertThat(ollamaCalls.get()).isEqualTo(1);
    }

    @Test
    void withoutAnNvidiaKeyNothingChangesFromBefore() {
        var result = ask(service(model(primaryCalls, null), model(secondaryCalls, null), null, model(ollamaCalls, "ollama answer")), "NVF4");

        assertThat(result).isInstanceOfSatisfying(AiChatService.ChatResult.Success.class, r -> assertThat(r.model()).isEqualTo("ollama-test"));
        assertThat(nvidiaCalls.get()).isZero();
    }

    @Test
    void nvidiaCanBeTheFirstHostedFallbackWhenThereIsNoSecondGeminiModel() {
        var result = ask(service(model(primaryCalls, null), null, model(nvidiaCalls, "nvidia answer"), model(ollamaCalls, "ollama answer")), "NVF5");

        assertThat(result).isInstanceOfSatisfying(AiChatService.ChatResult.Success.class, r -> assertThat(r.model()).isEqualTo("meta/llama-test"));
        assertThat(ollamaCalls.get()).isZero();
    }

    @Test
    void whenEveryModelFailsTheAnswerIsAControlledUnavailableNeverAStackTrace() {
        var result = ask(service(model(primaryCalls, null), model(secondaryCalls, null), model(nvidiaCalls, null), model(ollamaCalls, null)), "NVF6");

        assertThat(result).isInstanceOf(AiChatService.ChatResult.ProviderUnavailable.class);
    }
}
