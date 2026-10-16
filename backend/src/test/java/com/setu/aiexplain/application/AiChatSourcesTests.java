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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic (no real model): a stub {@link ChatModel} that "runs a tool" on whatever thread the service calls it
 * on, exactly like Spring AI's synchronous tool execution does. Regression: tools record their names in a ThreadLocal on
 * the worker thread, but the sources used to be drained on the CALLING thread, so every non-streamed answer (including
 * every Ollama fallback answer) came back with no sources at all.
 */
@SpringBootTest
class AiChatSourcesTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    AiToolService aiToolService;
    @Autowired
    AiToolInvocationRecorder invocationRecorder;
    @Autowired
    AiRateLimiter rateLimiter;
    @Autowired
    AiFacts facts;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private ChatClient clientThatRunsAttendanceTool() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                aiToolService.getStudentAttendance(null); // what the real tool-calling loop does, on this same thread
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Your attendance is 100%."))));
            }
        };
        return ChatClient.builder(model).build();
    }

    private AiChatService service(ChatClient client) {
        // "ollama" as the provider label = non-streaming primary path, the one the regression was in
        return new AiChatService(client, client, aiToolService, invocationRecorder, rateLimiter, facts, new AiConversationMemory(6, 30, 100), "ollama",
            "gemini-test", "ollama-test", 30L);
    }

    @Test
    void aNonStreamedAnswerReportsTheToolsItUsed() {
        var s = day13Fixtures.provisionEnrolledScenario("AIS1");
        TestAuthentication.authenticateAs(s.student().userId());

        var result = service(clientThatRunsAttendanceTool()).chat("How is my attendance?");

        assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
        assertThat(((AiChatService.ChatResult.Success) result).sources()).containsExactly("getStudentAttendance");
    }

    @Test
    void theToolNamesAlsoReachAStreamListenerEvenWhenNotStreaming() {
        var s = day13Fixtures.provisionEnrolledScenario("AIS2");
        TestAuthentication.authenticateAs(s.student().userId());
        List<String> tools = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        var result = service(clientThatRunsAttendanceTool()).chat("How is my attendance?", new AiChatService.StreamListener() {
            @Override
            public void onTool(String toolName) {
                tools.add(toolName);
            }

            @Override
            public void onToken(String chunk) {
                text.append(chunk);
            }
        });

        assertThat(result).isInstanceOf(AiChatService.ChatResult.Success.class);
        assertThat(tools).containsExactly("getStudentAttendance");
        assertThat(text.toString()).isEqualTo("Your attendance is 100%."); // a non-streamed answer is delivered as one chunk
    }

    @Test
    void sourcesDoNotLeakFromOneQuestionToTheNext() {
        var s = day13Fixtures.provisionEnrolledScenario("AIS3");
        TestAuthentication.authenticateAs(s.student().userId());
        var svc = service(clientThatRunsAttendanceTool());

        svc.chat("first question");
        var second = svc.chat("second question");

        assertThat(((AiChatService.ChatResult.Success) second).sources()).containsExactly("getStudentAttendance");
    }
}
