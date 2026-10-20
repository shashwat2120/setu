package com.setu.aiexplain.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With an NVIDIA key configured the client is built, without calling the provider (a client is only a configured object until a question is asked).
 * The key is a placeholder here and is never sent anywhere. The case with no key is {@link AiChatNvidiaConfigOffTests}.
 */
@SpringBootTest(properties = {"app.ai.nvidia.api-key=nvapi-placeholder-never-sent", "app.ai.nvidia.model=meta/llama-test"})
class AiChatNvidiaConfigTests {

    @Autowired
    ApplicationContext context;
    @Autowired
    AiChatService chatService;

    @Test
    void theClientIsBuiltAndNothingIsSentToTheProviderToBuildIt() {
        assertThat(context.containsBean("nvidiaChatClient")).isTrue();
    }

    @Test
    void theChatServiceIsGivenTheClientSoItIsTriedBeforeTheLocalModel() {
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(chatService, "nvidiaChatClient")).isNotNull();
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(chatService, "nvidiaModelName")).isEqualTo("meta/llama-test");
    }
}
