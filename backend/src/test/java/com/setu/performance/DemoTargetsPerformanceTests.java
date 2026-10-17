package com.setu.performance;

import com.setu.attendance.application.AttendanceRecordService;
import com.setu.attendance.application.AttendanceSessionService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The demo validation targets of canonical spec section 14, measured, and the performance gates of section 13 that can be checked in code:
 * <ul>
 *   <li>authenticated reads under 500 ms typical (p95 over repeated calls);</li>
 *   <li>the enrollment command under 800 ms typical;</li>
 *   <li>the QR scan acknowledgement under 1 s, including a burst of simultaneous scans by different students;</li>
 *   <li>a list endpoint is paginated and its page size is capped (the statement count, no N+1, is in {@code QueryCountTests}, which carries the
 *       instrumentation this class must not).</li>
 * </ul>
 * These are validation targets for the local demo, not SLAs: the numbers are in-process (no network between client and application) against the
 * development database, and say so in the report that prints them (docs/validation-report.md). Tagged {@code performance} and skipped by a plain
 * {@code mvn test} because timings on a machine that is busy with other tests prove nothing; run on purpose:
 * {@code ./mvnw test -DexcludedGroups=live-ollama -Dtest=DemoTargetsPerformanceTests}.
 */
@Tag("performance")
@SpringBootTest
@AutoConfigureMockMvc
class DemoTargetsPerformanceTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    AttendanceSessionService attendanceSessionService;
    @Autowired
    AttendanceRecordService attendanceRecordService;
    @Autowired
    JwtService jwtService;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    org.springframework.core.env.Environment environment;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId, UUID meetingId,
                            Day9TestFixtures.Faculty faculty) {
    }

    private Scenario scenario(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 60);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 50, room);
        UUID meetingId = day9Fixtures.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, programId, curriculumId, sectionId, meetingId, faculty);
    }

    private static long p95(List<Long> millis) {
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    private static long median(List<Long> millis) {
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static void report(String what, String target, List<Long> millis) {
        System.out.printf("[perf] %-46s n=%-3d median=%4d ms  p95=%4d ms  max=%4d ms   (target %s)%n", what, millis.size(), median(millis), p95(millis),
            Collections.max(millis), target);
    }

    private String adminBearer(Scenario s) {
        return "Bearer " + jwtService.issueAccessToken(s.tenant.adminUserId());
    }

    private long millis(Callable<?> call) throws Exception {
        long started = System.nanoTime();
        call.call();
        return (System.nanoTime() - started) / 1_000_000;
    }

    @Test
    void authenticatedReadsAreWellUnderHalfASecond() throws Exception {
        var s = scenario("PF1");
        for (int i = 0; i < 12; i++) {
            day9Fixtures.enrollStudent(day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF1S" + i), s.sectionId);
        }
        String bearer = adminBearer(s);
        String list = "/enrollment/enrollments/section/" + s.sectionId + "?tenantId=" + s.tenant.tenantId();
        String college = "/institution/colleges/" + s.tenant.tenantId();
        for (int i = 0; i < 5; i++) {   // warm up: class loading, connection pool, JIT
            mockMvc.perform(get(list).header("Authorization", bearer)).andReturn();
            mockMvc.perform(get(college).header("Authorization", bearer)).andReturn();
        }

        List<Long> lists = new ArrayList<>();
        List<Long> reads = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            lists.add(millis(() -> assertThat(mockMvc.perform(get(list).header("Authorization", bearer)).andReturn().getResponse().getStatus()).isEqualTo(200)));
            reads.add(millis(() -> assertThat(mockMvc.perform(get(college).header("Authorization", bearer)).andReturn().getResponse().getStatus()).isEqualTo(200)));
        }
        report("GET paginated list (12 enrollments)", "< 500 ms", lists);
        report("GET single resource", "< 500 ms", reads);

        assertThat(p95(lists)).as("list p95").isLessThan(500);
        assertThat(p95(reads)).as("single read p95").isLessThan(500);
    }

    @Test
    void theEnrollmentCommandIsWellUnderAFewHundredMilliseconds() throws Exception {
        var s = scenario("PF2");
        var warm = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF2W");
        day9Fixtures.enrollStudent(warm, s.sectionId);

        List<Long> timings = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF2S" + i);
            TestAuthentication.authenticateAs(student.userId());
            try {
                timings.add(millis(() -> enrollmentService.request(student.studentId(), s.sectionId)));
            } finally {
                TestAuthentication.clear();
            }
        }
        report("Enrollment command (full invariant chain)", "< 500-800 ms", timings);

        assertThat(p95(timings)).as("enrollment p95").isLessThan(800);
    }

    @Test
    void aBurstOfSimultaneousScansIsAcknowledgedWithinASecondEach() throws Exception {
        var s = scenario("PF3");
        int students = 40;
        List<Day8TestFixtures.Student> enrolled = new ArrayList<>();
        List<Day8TestFixtures.Student> warmUp = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF3W" + i);
            day9Fixtures.enrollStudent(student, s.sectionId);
            warmUp.add(student);
        }
        for (int i = 0; i < students; i++) {
            var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF3S" + i);
            day9Fixtures.enrollStudent(student, s.sectionId);
            enrolled.add(student);
        }
        TestAuthentication.authenticateAs(s.faculty.userId());
        AttendanceSessionService.OpenResult.Success session;
        try {
            session = (AttendanceSessionService.OpenResult.Success) attendanceSessionService.open(s.sectionId, s.meetingId,
                Instant.now().plus(1, ChronoUnit.HOURS));
        } finally {
            TestAuthentication.clear();
        }

        // The server runs at most server.tomcat.threads.max requests at once and queues the rest; a pool of that size is the same thing
        // (see ConnectionPoolSizingTests for why that number is bounded by the connection pool).
        int workers = Integer.parseInt(environment.getRequiredProperty("server.tomcat.threads.max"));
        // Ten scans first, so the burst measures steady-state work. The first scans after a start-up cost about 50 ms each (class loading, JIT,
        // Hibernate query plans) against about 15 ms once warm: measured with 1, 4, 12 and 24 workers, 40 scans took 2.1 s, 0.6 s, 0.7 s and 0.7 s.
        for (var student : warmUp) {
            TestAuthentication.authenticateAs(student.userId());
            try {
                attendanceRecordService.scan(session.sessionId(), session.rawToken());
            } finally {
                TestAuthentication.clear();
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Long> timings = Collections.synchronizedList(new ArrayList<>());
        AtomicLong burstFired = new AtomicLong();
        try {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<UUID>> futures = new ArrayList<>();
            for (var student : enrolled) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    TestAuthentication.authenticateAs(student.userId());
                    try {
                        var result = attendanceRecordService.scan(session.sessionId(), session.rawToken());
                        // from the moment the burst was fired, so a scan that had to wait for a free worker counts its wait, as a student would see it
                        timings.add((System.nanoTime() - burstFired.get()) / 1_000_000);
                        assertThat(result).as("every scan of the burst is accepted").isInstanceOf(AttendanceRecordService.ScanResult.Success.class);
                        return ((AttendanceRecordService.ScanResult.Success) result).recordId();
                    } finally {
                        TestAuthentication.clear();
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            burstFired.set(System.nanoTime());
            go.countDown();
            Set<UUID> records = new java.util.HashSet<>();
            for (var future : futures) {
                records.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(records).as("one distinct attendance record per student").hasSize(students);
        } finally {
            pool.shutdown();
        }
        report("QR scan acknowledgement (" + students + " at once, " + workers + " workers)", "< 1000 ms", timings);

        assertThat(p95(timings)).as("p95 of the burst").isLessThan(1000);
        assertThat(Collections.max(timings)).as("slowest scan of the burst").isLessThan(1000);
    }

    @Test
    void theListEndpointIsPaginatedAndCapsItsPageSize() throws Exception {
        var s = scenario("PF5");
        for (int i = 0; i < 4; i++) {
            day9Fixtures.enrollStudent(day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF5S" + i), s.sectionId);
        }
        var response = mockMvc.perform(get("/enrollment/enrollments/section/" + s.sectionId + "?tenantId=" + s.tenant.tenantId() + "&size=2&page=1")
            .header("Authorization", adminBearer(s))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        String body = response.getContentAsString();
        assertThat(body).contains("\"page\":1").contains("\"size\":2").contains("\"totalElements\":4").contains("\"totalPages\":2");

        var huge = mockMvc.perform(get("/enrollment/enrollments/section/" + s.sectionId + "?tenantId=" + s.tenant.tenantId() + "&size=1000000")
            .header("Authorization", adminBearer(s))).andReturn().getResponse();
        assertThat(huge.getStatus()).isEqualTo(200);
        assertThat(huge.getContentAsString()).as("a page can never be larger than the ceiling (PageSizeLimitFilter)").contains("\"size\":100");
    }
}
