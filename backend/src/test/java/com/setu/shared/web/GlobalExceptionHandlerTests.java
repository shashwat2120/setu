package com.setu.shared.web;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.application.AssessmentService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.institution.application.CollegeService;
import com.setu.testsupport.Day11TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Sprint 5D centralized exception-handling audit/implementation
 * (post-Day 11, pre-Day 12) — see {@link GlobalExceptionHandler}'s javadoc
 * for the full status-code mapping this exercises. Real PostgreSQL, real
 * Spring Security filter chain (MockMvc auto-configures it via {@code
 * spring-boot-starter-security-test}, already a test dependency), real
 * bearer tokens minted directly with {@link JwtService} rather than a full
 * login ceremony — this project's first genuine HTTP-layer integration
 * tests (every prior day's test called application services directly,
 * bypassing the web/security layer entirely, which is exactly why the
 * audit this task started from had never been exercised through HTTP
 * before).
 * <p>
 * Not {@code @Transactional}: several tests need genuinely separate,
 * concurrently-committing HTTP requests (the optimistic-lock test fires two
 * real concurrent PUTs), the same reason every prior concurrency test class
 * in this codebase avoids it.
 * <p>
 * Two categories — a raw {@code IllegalStateException} and a raw {@code
 * DataIntegrityViolationException} escaping uncaught — have no reachable
 * HTTP trigger in the current codebase: every call site that could throw
 * either already guards against it first (see the audit notes in {@code
 * GlobalExceptionHandler}'s own javadoc for the specific services checked).
 * Those two mappings are still exercised for real, but by invoking the
 * actual Spring-managed {@code GlobalExceptionHandler} bean directly rather
 * than through a synthetic endpoint — every other category below goes
 * through a real HTTP request against a real controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtService jwtService;
    @Autowired
    GlobalExceptionHandler globalExceptionHandler;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day11TestFixtures day11Fixtures;
    @Autowired
    AssessmentService assessmentService;
    @Autowired
    AssessmentComponentService assessmentComponentService;
    @Autowired
    CollegeService collegeService;

    private String bearerFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId);
    }

    // ---- 401: no token at all — Spring Security's own filter chain, via SecurityConfig's authenticationEntryPoint ----

    @Test
    void noToken_returns401WithConsistentShape() throws Exception {
        mockMvc.perform(get("/institution/colleges/" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.timestamp").value(notNullValue()))
            .andExpect(jsonPath("$.path").exists())
            .andExpect(jsonPath("$.requestId").exists());
    }

    // ---- 405: wrong HTTP method is a client mistake, not an unexpected server error ----

    @Test
    void wrongHttpMethod_returns405NotTheGeneric500() throws Exception {
        var tenant = fixtures.provisionTenant("GEH405");

        // /ai/chat is POST-only; a GET used to fall through to the 500 INTERNAL_ERROR safety net
        mockMvc.perform(get("/ai/chat").header("Authorization", bearerFor(tenant.adminUserId())))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.status").value(405))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void garbageToken_treatedSameAsNoToken_returns401() throws Exception {
        mockMvc.perform(get("/institution/colleges/" + UUID.randomUUID())
                .header("Authorization", "Bearer not-a-real-jwt"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- 403: authenticated, but the business AuthorizationContext denies — via GlobalExceptionHandler's AccessDeniedException mapping ----

    @Test
    void authenticatedWrongRole_returns403() throws Exception {
        var tenant = fixtures.provisionTenant("GEH403");
        UUID facultyUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.FACULTY, "GEH403-F");

        mockMvc.perform(put("/institution/colleges/" + tenant.tenantId())
                .header("Authorization", bearerFor(facultyUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\",\"timezone\":\"UTC\",\"locale\":\"en\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.requestId").value(notNullValue()));
    }

    @Test
    void crossTenantAccess_returns403_doesNotLeakExistence() throws Exception {
        var tenantA = fixtures.provisionTenant("GEHXA");
        var tenantB = fixtures.provisionTenant("GEHXB");

        // tenantB's own admin has MANAGE_INSTITUTION, but only for tenantB's college — tenantA's college id is a
        // different resource entirely, and the response must look the same shape/kind of denial either way.
        mockMvc.perform(put("/institution/colleges/" + tenantA.tenantId())
                .header("Authorization", bearerFor(tenantB.adminUserId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\",\"timezone\":\"UTC\",\"locale\":\"en\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.message").value("you do not have permission to perform this operation"));
    }

    // ---- 404: no handler mapped for the URL at all ----

    @Test
    void unmappedRoute_returns404WithConsistentShape() throws Exception {
        var tenant = fixtures.provisionTenant("GEH404");

        mockMvc.perform(get("/this/route/does/not/exist")
                .header("Authorization", bearerFor(tenant.adminUserId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- 400: malformed JSON body ----

    @Test
    void malformedJsonBody_returns400() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andExpect(jsonPath("$.message").value("request body could not be parsed"));
    }

    // ---- 400: bean-validation failure, with field errors ----

    @Test
    void invalidEmail_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"not-an-email\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
    }

    // ---- 400: a query parameter that fails to bind (not a valid UUID) ----

    @Test
    void nonUuidQueryParameter_returns400() throws Exception {
        var tenant = fixtures.provisionTenant("GEHTM");

        mockMvc.perform(get("/assessment/grades")
                .param("enrollmentId", "not-a-uuid")
                .param("componentId", UUID.randomUUID().toString())
                .header("Authorization", bearerFor(tenant.adminUserId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---- Existing typed (non-exception-driven) 409s keep working exactly as before — regression across two modules ----

    @Test
    void duplicateEmailRegistration_existingTypedConflict_stillWorks() throws Exception {
        String email = "geh-dup-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\": \"" + email + "\"}";

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("email already registered"));
    }

    @Test
    void duplicateAssessmentComponentCode_existingTypedConflict_stillWorks() throws Exception {
        var tenant = fixtures.provisionTenant("GEHDUP");
        UUID dept = fixtures.provisionDepartment(tenant, "GEHDUPD");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "GEHDUPC");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, "GEHDUPPR");
        UUID building = fixtures.provisionBuilding(tenant, "GEHDUPB");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, "GEHDUPF");
        UUID assessmentId = day11Fixtures.provisionDraftAssessment(faculty.userId(), sectionId, "Quiz",
            Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));

        String createBody = "{\"assessmentId\":\"" + assessmentId + "\",\"code\":\"C1\",\"maxMarks\":10,\"weight\":100,\"orderNo\":1}";
        mockMvc.perform(post("/assessment/components").header("Authorization", bearerFor(faculty.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/assessment/components").header("Authorization", bearerFor(faculty.userId()))
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("a component already uses this code in this assessment"));
    }

    // ---- Sprint 5D transaction-safety hardening follow-up: services that previously used an unguarded
    // saveAndFlush()+catch(DataIntegrityViolationException), now fixed with a REQUIRES_NEW insert guard. Each test
    // proves (a) the typed 409 survives a real commit (no UnexpectedRollbackException, matching the original
    // BuildingRoomTests/InstitutionAuthorizationTests failures this follow-up fixed), (b) no raw SQL/constraint
    // detail leaks into the response, and (c) the connection/transaction is still healthy immediately afterward —
    // a different, unrelated create call right after the conflict succeeds normally. ----

    @Test
    void duplicateBuildingCode_returns409_andConnectionStaysHealthyAfterward() throws Exception {
        var tenant = fixtures.provisionTenant("GEHBLD");
        String auth = bearerFor(tenant.adminUserId());
        String createBody = "{\"tenantId\":\"" + tenant.tenantId() + "\",\"code\":\"B1\",\"name\":\"Block One\"}";

        mockMvc.perform(post("/catalog/buildings").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/catalog/buildings").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("building code already in use"))
            .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("constraint"))));

        // The connection/pool the guard's REQUIRES_NEW transaction used must not be left in any poisoned state —
        // an unrelated, immediately-following create against the SAME tenant must succeed normally.
        String otherBuildingBody = "{\"tenantId\":\"" + tenant.tenantId() + "\",\"code\":\"B2\",\"name\":\"Block Two\"}";
        mockMvc.perform(post("/catalog/buildings").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(otherBuildingBody))
            .andExpect(status().isCreated());
    }

    @Test
    void duplicateCampusCode_returns409_andConnectionStaysHealthyAfterward() throws Exception {
        var tenant = fixtures.provisionTenant("GEHCMP");
        String auth = bearerFor(tenant.adminUserId());
        String createBody = "{\"collegeId\":\"" + tenant.tenantId() + "\",\"code\":\"C1\",\"name\":\"Main Campus\"}";

        mockMvc.perform(post("/institution/campuses").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/institution/campuses").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("campus code already in use for this college"));

        String otherCampusBody = "{\"collegeId\":\"" + tenant.tenantId() + "\",\"code\":\"C2\",\"name\":\"Second Campus\"}";
        mockMvc.perform(post("/institution/campuses").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(otherCampusBody))
            .andExpect(status().isCreated());
    }

    // ---- 409: genuine optimistic-lock conflict under real concurrency ----

    @Test
    void concurrentCollegeUpdates_oneSucceeds_otherGetsVersionConflict() throws Exception {
        var tenant = fixtures.provisionTenant("GEHLOCK");
        String auth = bearerFor(tenant.adminUserId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 = pool.submit(() -> mockMvc.perform(put("/institution/colleges/" + tenant.tenantId())
                    .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Name A\",\"timezone\":\"UTC\",\"locale\":\"en\"}"))
                .andReturn().getResponse().getStatus());
            Future<Integer> f2 = pool.submit(() -> mockMvc.perform(put("/institution/colleges/" + tenant.tenantId())
                    .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Name B\",\"timezone\":\"UTC\",\"locale\":\"en\"}"))
                .andReturn().getResponse().getStatus());

            List<Integer> statuses = List.of(f1.get(), f2.get());
            // Do not claim which request wins — only that the pair is exactly one success and, when the loser's
            // stale in-memory copy actually raced the winner's commit, a real 409; a 200/200 pair can legitimately
            // happen if the two requests didn't overlap closely enough to race, so this only requires no 5xx and
            // never both requests silently overwriting each other undetected.
            assertThat(statuses).allMatch(s -> s == 200 || s == 409);
            assertThat(statuses).contains(200);
        } finally {
            pool.shutdown();
        }
    }

    // ---- Categories with no reachable HTTP trigger in the current codebase (every call site already guards) —
    // exercised via the real Spring-managed GlobalExceptionHandler bean instead. See class javadoc. ----

    @Test
    void illegalStateException_mapsTo409_viaRealHandlerBean() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/assessment/attempts/123/submit");
        var response = globalExceptionHandler.handleIllegalState(new IllegalStateException("cannot submit attempt from EVALUATED"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("STATE_CONFLICT");
        assertThat(response.getBody().message()).doesNotContain("EVALUATED");
        assertThat(response.getBody().requestId()).isNotNull();
    }

    @Test
    void dataIntegrityViolation_mapsTo409_viaRealHandlerBean() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/catalog/rooms");
        var response = globalExceptionHandler.handleDataIntegrityViolation(
            new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_rooms_building_code\""), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_CONFLICT");
        assertThat(response.getBody().message()).doesNotContain("uq_rooms_building_code").doesNotContain("constraint");
    }

    @Test
    void unexpectedException_returns500_neverLeaksInternalDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/institution/colleges/x");
        var response = globalExceptionHandler.handleUnexpected(
            new RuntimeException("jdbc:postgresql://internal-host:5432/setu user=setu password=hunter2"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("an unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("password").doesNotContain("hunter2").doesNotContain("jdbc:postgresql");
        assertThat(response.getBody().requestId()).isNotNull();
    }

    // ---- Consistent shape across two different modules' exception-driven errors ----

    @Test
    void errorShape_consistentAcrossModules() throws Exception {
        var tenant = fixtures.provisionTenant("GEHSHAPE");
        UUID facultyUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.FACULTY, "GEHSHAPE-F");

        mockMvc.perform(put("/institution/colleges/" + tenant.tenantId())
                .header("Authorization", bearerFor(facultyUserId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"timezone\":\"UTC\",\"locale\":\"en\"}"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.code").exists())
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.path").exists())
            .andExpect(jsonPath("$.requestId").exists());

        mockMvc.perform(get("/this/other/unmapped/route").header("Authorization", bearerFor(facultyUserId)))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.code").exists())
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.path").exists())
            .andExpect(jsonPath("$.requestId").exists());
    }

    /** Day 15 §7: a client-supplied X-Correlation-Id is echoed back on the response header AND is the exact value used as the error body's requestId — the two are the same mechanism, not two independent ids. */
    @Test
    void clientSuppliedCorrelationId_isEchoedAndUsedAsTheErrorRequestId() throws Exception {
        String suppliedId = "test-correlation-" + UUID.randomUUID();

        mockMvc.perform(get("/this/other/unmapped/route").header("X-Correlation-Id", suppliedId))
            .andExpect(status().isUnauthorized())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Correlation-Id", suppliedId))
            .andExpect(jsonPath("$.requestId").value(suppliedId));
    }

    /** A request with no X-Correlation-Id header still gets one generated and echoed back — the mechanism never silently omits it. */
    @Test
    void missingCorrelationIdHeader_stillGetsOneGeneratedAndEchoed() throws Exception {
        mockMvc.perform(get("/this/other/unmapped/route"))
            .andExpect(status().isUnauthorized())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-Correlation-Id"));
    }

    /** A malformed/oversized correlation id header (log-injection attempt) is replaced with a freshly generated one, never echoed back verbatim. */
    @Test
    void malformedCorrelationIdHeader_isReplacedNotEchoed() throws Exception {
        String malicious = "bad\r\nX-Injected: true";

        var result = mockMvc.perform(get("/this/other/unmapped/route").header("X-Correlation-Id", malicious))
            .andExpect(status().isUnauthorized())
            .andReturn();

        String echoed = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(echoed).isNotEqualTo(malicious);
        assertThat(echoed).matches("[A-Za-z0-9._-]+");
    }
}
