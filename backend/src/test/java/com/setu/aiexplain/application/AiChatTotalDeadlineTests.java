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

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole answer has one overall time limit. Each model used to get a fresh budget of its own (the primary 20 s, the hosted fallback up to 60 s, the local one another 60 s),
 * so a bad day cost a student well over a minute, and in the worst case more than the gateway waits, before they were told anything. These tests use models that really
 * take time, so the clock is the real one: the chain never runs past the total, a model with too little time left is not started, a later model gets exactly what is
 * left, a model that ran out of time is cancelled, and with no overall limit it is the old behaviour.
 */
@SpringBootTest
class AiChatTotalDeadlineTests {

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
    private final AtomicInteger ollamaCalls = new AtomicInteger();

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    /** A model that takes {@code delayMillis}, then answers with {@code text}; {@code interrupted} is counted down if it is stopped while it waits. */
    private static ChatClient model(AtomicInteger calls, long delayMillis, String text, CountDownLatch interrupted) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    if (interrupted != null) {
                        interrupted.countDown();
                    }
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("stopped");
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

    private AiChatService service(ChatClient primary, long primaryBudgetSeconds, ChatClient nvidia, ChatClient ollama, long totalSeconds) {
        return new AiChatService(primary, ollama, aiToolService, invocationRecorder, rateLimiter, facts,
            new AiConversationMemory(6, 30, 100), "google-genai", "gemini-primary", "ollama-test", 30L, primaryBudgetSeconds)
            .withNvidia(nvidia, "nvidia-test")
            .withPrimaryCooldown(0)
            .withTotalTimeout(totalSeconds);
    }

    private record Timed(AiChatService.ChatResult result, Duration took) {
    }

    private Timed ask(AiChatService service, UUID user) {
        TestAuthentication.authenticateAs(user);
        long start = System.nanoTime();
        var result = service.chat("How am I doing?");
        return new Timed(result, Duration.ofNanos(System.nanoTime() - start));
    }

    @Test
    void theWholeChainNeverRunsPastTheTotalEvenWhenEveryModelHangs() {
        var s = day13Fixtures.provisionEnrolledScenario("DLN1");
        var service = service(model(primaryCalls, 20_000, "late", null), 2, model(nvidiaCalls, 20_000, "late", null), model(ollamaCalls, 20_000, "late", null), 6);

        var timed = ask(service, s.student().userId());

        assertThat(timed.result()).isInstanceOf(AiChatService.ChatResult.ProviderUnavailable.class);
        assertThat(timed.took()).as("two seconds for the primary, the hosted model gets the three of the four that are left (whole seconds)").isBetween(Duration.ofMillis(4500), Duration.ofMillis(6800));
        assertThat(primaryCalls).hasValue(1);
        assertThat(nvidiaCalls).hasValue(1);
        assertThat(ollamaCalls).as("nothing was left for it").hasValue(0);
    }

    @Test
    void aModelWithTooLittleTimeLeftIsNotStartedAndTheStudentIsToldSoAtOnce() {
        var s = day13Fixtures.provisionEnrolledScenario("DLN2");
        var service = service(model(primaryCalls, 20_000, "late", null), 2, model(nvidiaCalls, 20_000, "late", null), model(ollamaCalls, 20_000, "late", null), 4);

        var timed = ask(service, s.student().userId());

        assertThat(timed.result()).isInstanceOf(AiChatService.ChatResult.ProviderUnavailable.class);
        assertThat(timed.took()).as("the primary's two seconds, then only two of the four were left").isLessThan(Duration.ofMillis(3800));
        assertThat(nvidiaCalls).as("two seconds is not enough to start a model").hasValue(0);
        assertThat(ollamaCalls).hasValue(0);
    }

    @Test
    void aLaterModelGetsWhatIsLeftAndCanStillAnswerInIt() {
        var s = day13Fixtures.provisionEnrolledScenario("DLN3");
        var service = service(model(primaryCalls, 20_000, "late", null), 2, model(nvidiaCalls, 3_000, "hosted answer", null), model(ollamaCalls, 20_000, "late", null), 10);

        var timed = ask(service, s.student().userId());

        assertThat(timed.result()).isInstanceOf(AiChatService.ChatResult.Success.class);
        assertThat(((AiChatService.ChatResult.Success) timed.result()).model()).isEqualTo("nvidia-test");
        assertThat(timed.took()).as("two seconds lost on the primary, three on the hosted model").isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void aModelThatCannotFinishInWhatIsLeftIsStoppedAtTheTotalNotLaterAndIsThenCancelled() throws Exception {
        var s = day13Fixtures.provisionEnrolledScenario("DLN4");
        var stopped = new CountDownLatch(1);
        var service = service(model(primaryCalls, 20_000, "late", null), 2, model(nvidiaCalls, 20_000, "late", stopped), model(ollamaCalls, 20_000, "late", null), 8);

        var timed = ask(service, s.student().userId());

        assertThat(timed.result()).isInstanceOf(AiChatService.ChatResult.ProviderUnavailable.class);
        assertThat(timed.took()).as("not the hosted model's own 30 s").isBetween(Duration.ofSeconds(6), Duration.ofMillis(9500));
        assertThat(stopped.await(3, TimeUnit.SECONDS)).as("the hosted call that ran out of time was cancelled, not left running in the background").isTrue();
    }

    @Test
    void withoutAnOverallLimitItIsTheOldBehaviourAndEachModelKeepsItsOwnBudget() {
        var s = day13Fixtures.provisionEnrolledScenario("DLN5");
        var service = service(model(primaryCalls, 20_000, "late", null), 1, model(nvidiaCalls, 3_000, "hosted answer", null), model(ollamaCalls, 20_000, "late", null), 0);

        var timed = ask(service, s.student().userId());

        assertThat(timed.result()).isInstanceOf(AiChatService.ChatResult.Success.class);
        assertThat(((AiChatService.ChatResult.Success) timed.result()).model()).isEqualTo("nvidia-test");
    }

    @Test
    void aModelThatAnswersPromptlyIsUnaffectedByTheLimit() {
        var s = day13Fixtures.provisionEnrolledScenario("DLN6");
        var service = service(model(primaryCalls, 50, "primary answer", null), 2, model(nvidiaCalls, 20_000, "late", null), model(ollamaCalls, 20_000, "late", null), 10);

        var timed = ask(service, s.student().userId());

        assertThat(timed.result()).isInstanceOf(AiChatService.ChatResult.Success.class);
        assertThat(((AiChatService.ChatResult.Success) timed.result()).model()).isEqualTo("gemini-primary");
        assertThat(timed.took()).isLessThan(Duration.ofSeconds(2));
        assertThat(nvidiaCalls).hasValue(0);
    }
}
