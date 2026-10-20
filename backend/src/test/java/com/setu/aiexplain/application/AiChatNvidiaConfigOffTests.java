package com.setu.aiexplain.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** With no NVIDIA key nothing about it exists: no client, and the chat service is not given one, so the chain is exactly what it was before. */
@SpringBootTest
class AiChatNvidiaConfigOffTests {

    @Autowired
    ApplicationContext context;
    @Autowired
    AiChatService chatService;

    @Test
    void thereIsNoNvidiaClient() {
        assertThat(context.containsBean("nvidiaChatClient")).isFalse();
    }

    @Test
    void theChatServiceHasNoNvidiaStep() {
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(chatService, "nvidiaChatClient")).isNull();
    }
}
