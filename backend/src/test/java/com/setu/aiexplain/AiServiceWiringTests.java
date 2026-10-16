package com.setu.aiexplain;

import com.setu.aiexplain.infrastructure.facts.RemoteAiFacts;
import com.setu.shared.aifacts.AiFacts;
import com.setu.shared.security.Jwks;
import com.setu.shared.security.JwksKeySource;
import com.setu.shared.websocket.LiveBroadcastRequest;
import com.setu.testsupport.BlockingStubServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.setu.aiexplain.application.AiToolService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The AI service on its own (docs/contracts/ai-service.md): no datasource and nothing from any other module in its context; every fact and
 * every audit record goes to the data service with the <i>user's own</i> token; a question can be put as a background job that moves
 * QUEUED, RUNNING, COMPLETED and is announced on the owner's own topic; and when the data service cannot be reached the answer is a refusal,
 * not a guess. The model is a scripted stand-in, so this tests the service around it, not a provider.
 */
@SpringBootTest(classes = AiServiceApplication.class, properties = {"setu.service=ai", "setu.auth.mode=remote", "app.readmodel.consumer.enabled=false",
    "app.notification.consumer.enabled=false", "spring.flyway.enabled=false", "app.ai.rate-limit.max-requests=1000",
    "setu.ai-facts.breaker.minimum-calls=100000"})          // one shared client across these tests: its breaker is covered in RemoteAiFactsTests
@AutoConfigureMockMvc
@Import({AiServiceWiringTests.IdentityKeys.class, AiServiceWiringTests.ScriptedModel.class, AiServiceWiringTests.EventRecorder.class})
class AiServiceWiringTests {

    private static final KeyPair IDENTITY_KEY = newPair();
    private static BlockingStubServer dataService;

    @BeforeAll
    static void startDataService() throws Exception {
        if (dataService == null) {
            dataService = new BlockingStubServer();
        }
    }

    @AfterAll
    static void stopDataService() throws Exception {
        dataService.close();
    }

    @DynamicPropertySource
    static void dataServiceUrl(DynamicPropertyRegistry registry) throws Exception {
        if (dataService == null) {
            dataService = new BlockingStubServer();
        }
        registry.add("setu.ai-facts.url", () -> dataService.uri().toString());
    }

    @TestConfiguration
    static class IdentityKeys {
        @Bean
        @Primary
        JwksKeySource testJwksKeySource() {
            String jwks = Jwks.toJson(Map.of("test-key", IDENTITY_KEY.getPublic()));
            return new JwksKeySource(() -> jwks, Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofSeconds(30));
        }
    }

    /**
     * Both providers are a script, so no real model is involved: it asks for the caller's own attendance the way a tool-calling model would
     * (a real tool call, through the real tool class and the real remote client), then answers with a fixed sentence. The real clients are
     * swapped as they are created, so the result does not depend on the order configuration classes are read in.
     */
    @TestConfiguration
    static class ScriptedModel {
        private static ChatClient client(ObjectProvider<AiToolService> tools) {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    tools.getObject().getStudentAttendance(null);
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("You are on track."))));
                }

                @Override
                public ChatOptions getDefaultOptions() {
                    return ToolCallingChatOptions.builder().build();
                }
            };
            return ChatClient.builder(model).build();
        }

        @Bean
        static BeanPostProcessor scriptedProviders(ObjectProvider<AiToolService> tools) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof ChatClient && (beanName.equals("aiChatClient") || beanName.equals("fallbackChatClient"))
                        ? client(tools) : bean;
                }
            };
        }
    }

    @TestConfiguration
    static class EventRecorder {
        static final List<LiveBroadcastRequest> SEEN = new CopyOnWriteArrayList<>();

        @EventListener
        void on(LiveBroadcastRequest request) {
            SEEN.add(request);
        }
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    AiFacts facts;

    private UUID user;
    private UUID tenant;

    private static KeyPair newPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void prepare() {
        user = UUID.randomUUID();
        tenant = UUID.randomUUID();
        EventRecorder.SEEN.clear();
        dataService.seen.clear();
        dataService.dropWithoutAnswer = false;
        dataService.delayMillis = 0;
        dataService.responder = asked -> {
            String line = asked.requestLine();
            if (line.startsWith("GET /ai-facts/me")) {
                return BlockingStubServer.json(200, "{\"userId\":\"" + user + "\",\"tenantId\":\"" + tenant + "\"}");
            }
            if (line.startsWith("GET /ai-facts/attendance")) {
                return BlockingStubServer.json(200, "{\"percentage\":93.0,\"presentOrLate\":28,\"total\":30}");
            }
            if (line.startsWith("POST /ai-facts/audit")) {
                return "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n";
            }
            return BlockingStubServer.json(404, "{}");
        };
    }

    private String token(UUID who) {
        return Jwts.builder().header().keyId("test-key").and()
            .subject(who.toString()).issuer("setu").audience().add("setu-api").and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id(UUID.randomUUID().toString())
            .signWith(IDENTITY_KEY.getPrivate(), Jwts.SIG.ES256).compact();
    }

    private String submitJob(String bearer) throws Exception {
        var response = mockMvc.perform(post("/ai/jobs").header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How am I doing?\"}")).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
        String body = response.getContentAsString();
        return body.replaceAll(".*\"jobId\":\"([0-9a-f-]{36})\".*", "$1");
    }

    private String jobStatus(String bearer, String jobId) throws Exception {
        var response = mockMvc.perform(get("/ai/jobs/" + jobId).header("Authorization", "Bearer " + bearer)).andReturn().getResponse();
        return response.getStatus() + " " + response.getContentAsString();
    }

    // --- what the service is

    @Test
    void thereIsNoDatasourceAndNothingFromAnyOtherModuleInTheContext() {
        List<String> persistence = Arrays.stream(context.getBeanDefinitionNames())
            .map(context::getType).filter(t -> t != null).map(Class::getName)
            .filter(n -> n.startsWith("javax.sql.") || n.contains("DataSource") || n.contains("EntityManagerFactory") || n.contains("JdbcClient")
                || n.contains("JdbcTemplate") || n.contains("Flyway") || n.contains("JpaRepositor"))
            .toList();
        assertThat(persistence).as("no persistence of any kind").isEmpty();

        List<String> foreign = Arrays.stream(context.getBeanDefinitionNames())
            .map(context::getType).filter(t -> t != null && t.getName().startsWith("com.setu."))
            .map(Class::getName)
            .filter(n -> !n.startsWith("com.setu.aiexplain.") && !n.startsWith("com.setu.shared.") && !n.startsWith("com.setu.testsupport."))
            .filter(n -> !n.startsWith("com.setu.AiServiceApplication") && !n.contains("AiServiceWiringTests"))
            .toList();
        assertThat(foreign).as("only the AI module and the kernel").isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(t -> t != null).map(Class::getName))
            .noneMatch(n -> n.startsWith("com.setu.shared.governance.") || n.startsWith("com.setu.shared.readmodel.")
                || n.startsWith("com.setu.shared.admin.") || n.startsWith("com.setu.shared.seed.") || n.startsWith("com.setu.aifacts."));
    }

    @Test
    void itsFactsComeFromTheDataServiceOverTheNetwork() {
        assertThat(facts).isInstanceOf(RemoteAiFacts.class);
    }

    // --- who may ask

    @Test
    void aTokenIsRequiredForQuestionsAndJobs() throws Exception {
        assertThat(mockMvc.perform(post("/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}")).andReturn().getResponse().getStatus())
            .isEqualTo(401);
        assertThat(mockMvc.perform(post("/ai/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}")).andReturn().getResponse().getStatus())
            .isEqualTo(401);
        assertThat(mockMvc.perform(get("/ai/jobs/" + UUID.randomUUID())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post("/ai/chat").header("Authorization", "Bearer garbage").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"hi\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(dataService.seen).as("nothing reached the data service").isEmpty();
    }

    // --- a question, with the user's own token everywhere

    @Test
    void aQuestionIsAnsweredAndEveryCallToTheDataServiceCarriesTheAskersOwnToken() throws Exception {
        String bearer = token(user);

        var response = mockMvc.perform(post("/ai/chat").header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How am I doing?\"}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("You are on track.");
        assertThat(response.getContentAsString()).as("the tool the model used is reported as a source").contains("\"sources\":[\"getStudentAttendance\"]");
        assertThat(dataService.seen).extracting(BlockingStubServer.Seen::requestLine)
            .anyMatch(l -> l.startsWith("GET /ai-facts/me")).anyMatch(l -> l.startsWith("GET /ai-facts/attendance"))
            .anyMatch(l -> l.startsWith("POST /ai-facts/audit"));
        assertThat(dataService.seen).allSatisfy(seen -> assertThat(seen.header("Authorization")).isEqualTo("Bearer " + bearer));
        assertThat(dataService.seen.stream().filter(s -> s.requestLine().startsWith("POST /ai-facts/audit")).findFirst().orElseThrow().body())
            .contains("AI_CHAT_COMPLETED");
    }

    @Test
    void withoutTheDataServiceTheAnswerIsARefusalNotAGuess() throws Exception {
        dataService.responder = asked -> BlockingStubServer.json(503, "{}");

        var response = mockMvc.perform(post("/ai/chat").header("Authorization", "Bearer " + token(user)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How am I doing?\"}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("You are on track.");
    }

    @Test
    void anAnswerThatCannotBeRecordedIsNotGiven() throws Exception {
        var normal = dataService.responder;
        dataService.responder = asked -> asked.requestLine().startsWith("POST /ai-facts/audit") ? BlockingStubServer.json(500, "{}") : normal.apply(asked);

        var response = mockMvc.perform(post("/ai/chat").header("Authorization", "Bearer " + token(user)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How am I doing?\"}")).andReturn().getResponse();

        assertThat(response.getStatus()).as("fail closed: no record, no answer").isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("You are on track.");
    }

    // --- background jobs

    @Test
    void aJobIsAcceptedAtOnceThenRunsToCompletionAndIsAnnouncedOnTheOwnersTopicOnly() throws Exception {
        String bearer = token(user);

        String jobId = submitJob(bearer);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jobStatus(bearer, jobId)).startsWith("200").contains("\"status\":\"COMPLETED\""));
        String done = jobStatus(bearer, jobId);
        assertThat(done).contains("You are on track.").contains("\"jobId\":\"" + jobId + "\"");

        List<String> statuses = EventRecorder.SEEN.stream().filter(e -> e.destination().equals("/topic/ai/jobs/" + user))
            .map(e -> ((Map<?, ?>) e.payload()).get("status").toString()).toList();
        assertThat(statuses).as("every change of status, in order").containsExactly("QUEUED", "RUNNING", "COMPLETED");
        assertThat(EventRecorder.SEEN).allSatisfy(e -> {
            assertThat(e.destination()).isEqualTo("/topic/ai/jobs/" + user);
            assertThat(e.payload().toString()).as("status only, never the answer").doesNotContain("You are on track.");
        });
        assertThat(dataService.seen).allSatisfy(seen -> assertThat(seen.header("Authorization")).isEqualTo("Bearer " + bearer));
        assertThat(dataService.seen.stream().map(BlockingStubServer.Seen::body).toList()).anyMatch(b -> b.contains("AI_JOB_SUBMITTED"))
            .anyMatch(b -> b.contains("AI_JOB_COMPLETED"));
    }

    @Test
    void aJobBelongsToItsOwnerAlone() throws Exception {
        String jobId = submitJob(token(user));
        UUID other = UUID.randomUUID();

        String theirs = jobStatus(token(other), jobId);

        assertThat(theirs).startsWith("404").doesNotContain("You are on track.");
        assertThat(jobStatus(token(other), UUID.randomUUID().toString())).as("indistinguishable from a job that never existed").startsWith("404");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jobStatus(token(user), jobId)).contains("COMPLETED"));
    }

    @Test
    void aJobThatCannotBeRecordedFailsInsteadOfAnsweringAndNothingIsAcceptedWhenTheCallerIsUnknown() throws Exception {
        String bearer = token(user);
        var normal = dataService.responder;
        dataService.responder = asked -> asked.requestLine().startsWith("POST /ai-facts/audit") && asked.body().contains("AI_CHAT_COMPLETED")
            ? BlockingStubServer.json(500, "{}") : normal.apply(asked);

        String jobId = submitJob(bearer);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jobStatus(bearer, jobId)).contains("\"status\":\"FAILED\""));
        assertThat(jobStatus(bearer, jobId)).contains("\"failure\":\"unavailable\"").doesNotContain("You are on track.");

        dataService.responder = asked -> BlockingStubServer.json(503, "{}");
        var refused = mockMvc.perform(post("/ai/jobs").header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"How am I doing?\"}")).andReturn().getResponse();
        assertThat(refused.getStatus()).as("an unidentifiable caller gets nothing queued").isEqualTo(503);
    }

    @Test
    void anInvalidQuestionIsRejectedBeforeAnythingIsQueued() throws Exception {
        for (String body : new String[] {"{\"message\":\"\"}", "{\"message\":\"   \"}", "{}", "{\"message\":\"" + "x".repeat(2001) + "\"}"}) {
            var response = mockMvc.perform(post("/ai/jobs").header("Authorization", "Bearer " + token(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
            assertThat(response.getStatus()).as(body.length() > 60 ? "overlong" : body).isEqualTo(400);
        }
        assertThat(dataService.seen).as("not even the data service was asked").isEmpty();
    }
}
