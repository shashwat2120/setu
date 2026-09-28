package com.setu.attendance.integration;

import com.setu.attendance.application.AttendanceRecordService;
import com.setu.attendance.application.AttendanceSessionService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.notification.infrastructure.websocket.StompUserPrincipal;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 9 §8-9, §18 WebSocket/STOMP authorization tests. Drives the REAL
 * production beans ({@code StompAuthorizationChannelInterceptor},
 * {@code AttendanceSubscriptionAuthorizer}, the real
 * {@code SimpleBrokerMessageHandler}, real JWT/PostgreSQL-backed
 * authorization) directly through Spring's {@code clientInboundChannel} /
 * {@code clientOutboundChannel} message beans, rather than through a real
 * network socket.
 * <p>
 * A genuine socket-level test (embedded Tomcat, {@code RANDOM_PORT}, a real
 * {@code WebSocketStompClient}) was attempted first but the embedded
 * server cannot start in this execution environment: Tomcat's NIO
 * connector calls {@code Selector.open()}, which on this JDK/Windows build
 * opens its internal wakeup pipe over a Unix Domain Socket
 * ({@code sun.nio.ch.PipeImpl$Initializer$LoopbackConnector}), and that
 * UDS connect fails here with {@code SocketException: Invalid argument} —
 * confirmed independent of {@code java.net.preferIPv4Stack} and of the
 * legacy {@code WindowsSelectorProvider}, so it is a genuine host/JDK
 * limitation, not a sandboxing or code issue. Disclosed rather than
 * silently skipped: this test instead drives the exact same interceptor
 * and broker beans the real WebSocket transport would invoke, which is
 * everything Day 9's authorization requirements are actually about — only
 * the raw TCP/WebSocket framing layer (Spring's own, not this project's
 * code) goes unexercised.
 * <p>
 * {@link StompUserPrincipal}'s constructor had to become public for this
 * (previously package-private in {@code notification.infrastructure.
 * websocket}) — a real WebSocket session normally supplies one via {@code
 * StompAuthorizationChannelInterceptor.handleConnect} only; a directly
 * constructed one here simulates exactly what that would have already
 * resolved onto the SUBSCRIBE frame's accessor, since bypassing the socket
 * layer also bypasses Spring's session-to-principal caching that normally
 * carries it from CONNECT to later frames.
 */
@SpringBootTest
class AttendanceStompAuthorizationTests {

    @Autowired
    @Qualifier("clientInboundChannel")
    SubscribableChannel clientInboundChannel;

    @Autowired
    @Qualifier("clientOutboundChannel")
    SubscribableChannel clientOutboundChannel;

    @Autowired
    JwtService jwtService;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    AttendanceSessionService attendanceSessionService;
    @Autowired
    AttendanceRecordService attendanceRecordService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             UUID meetingId, Day9TestFixtures.Faculty faculty) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        UUID meetingId = day9Fixtures.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        return new Scenario(tenant, programId, curriculumId, sectionId, meetingId, faculty);
    }

    private Message<byte[]> connectFrame(String sessionId, String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        if (authorizationHeader != null) {
            accessor.addNativeHeader("Authorization", authorizationHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeFrame(String sessionId, UUID userId, String destination, String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setDestination(destination);
        accessor.setUser(new StompUserPrincipal(userId));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void authenticatedConnectionSucceeds() {
        var s = setUp("WS1");
        String token = jwtService.issueAccessToken(s.faculty.userId());

        assertThatCode(() -> clientInboundChannel.send(connectFrame(UUID.randomUUID().toString(), "Bearer " + token)))
            .doesNotThrowAnyException();
    }

    @Test
    void connectionWithoutBearerTokenIsDenied() {
        assertThatThrownBy(() -> clientInboundChannel.send(connectFrame(UUID.randomUUID().toString(), null)))
            .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void connectionWithInvalidTokenIsDenied() {
        assertThatThrownBy(() -> clientInboundChannel.send(connectFrame(UUID.randomUUID().toString(), "Bearer not-a-real-token")))
            .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void assignedFacultyCanSubscribeToOwnSection() {
        var s = setUp("WS4");

        assertThatCode(() -> clientInboundChannel.send(
            subscribeFrame(UUID.randomUUID().toString(), s.faculty.userId(), "/topic/attendance/section/" + s.sectionId, "sub-0")))
            .doesNotThrowAnyException();
    }

    @Test
    void unassignedFacultyCannotSubscribeToAnotherSection() {
        var s = setUp("WS5");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "WS5-OTHER");

        assertThatThrownBy(() -> clientInboundChannel.send(
            subscribeFrame(UUID.randomUUID().toString(), otherFacultyUserId, "/topic/attendance/section/" + s.sectionId, "sub-0")))
            .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void studentCannotSubscribeToInstructorBoard() {
        var s = setUp("WS6");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "WS6S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        assertThatThrownBy(() -> clientInboundChannel.send(
            subscribeFrame(UUID.randomUUID().toString(), student.userId(), "/topic/attendance/section/" + s.sectionId, "sub-0")))
            .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void facultyFromAnotherTenantCannotSubscribe() {
        var s = setUp("WS7A");
        var otherTenant = fixtures.provisionTenant("WS7B");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(otherTenant.tenantId(), RoleCode.FACULTY, "WS7B-F");

        assertThatThrownBy(() -> clientInboundChannel.send(
            subscribeFrame(UUID.randomUUID().toString(), otherFacultyUserId, "/topic/attendance/section/" + s.sectionId, "sub-0")))
            .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanSubscribeToAnySectionInTenant() {
        var s = setUp("WS8");

        assertThatCode(() -> clientInboundChannel.send(
            subscribeFrame(UUID.randomUUID().toString(), s.tenant.adminUserId(), "/topic/attendance/section/" + s.sectionId, "sub-0")))
            .doesNotThrowAnyException();
    }

    /**
     * {@code SimpleBrokerMessageHandler} only ever writes to a session's outbound channel if that session has a
     * {@code SessionInfo} entry, which it records solely while processing a CONNECT command (see {@code
     * SimpleBrokerMessageHandler.handleMessageInternal}'s CONNECT branch) — a SUBSCRIBE for a session with no prior
     * CONNECT is registered in the subscription registry (matching still works) but every subsequent broadcast to
     * it silently no-ops (confirmed by decompiling {@code lambda$sendMessageToSubscribers$0}: a null {@code
     * SessionInfo} lookup skips the send with no log, no exception). A real WebSocket client always sends CONNECT
     * before SUBSCRIBE, so this mirrors that, not a synthetic requirement.
     */
    private void connectSession(String sessionId, UUID userId) {
        String token = jwtService.issueAccessToken(userId);
        clientInboundChannel.send(connectFrame(sessionId, "Bearer " + token));
    }

    @Test
    void liveAttendanceEventReachesOnlyTheAuthorizedSubscriber() throws Exception {
        var s = setUp("WS9");
        UUID unassignedFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "WS9-OTHER");
        String authorizedSessionId = "sess-auth-" + UUID.randomUUID();
        String unauthorizedSessionId = "sess-unauth-" + UUID.randomUUID();
        String destination = "/topic/attendance/section/" + s.sectionId;

        connectSession(authorizedSessionId, s.faculty.userId());
        connectSession(unauthorizedSessionId, unassignedFacultyUserId);
        clientInboundChannel.send(subscribeFrame(authorizedSessionId, s.faculty.userId(), destination, "sub-0"));
        assertThatThrownBy(() -> clientInboundChannel.send(subscribeFrame(unauthorizedSessionId, unassignedFacultyUserId, destination, "sub-0")))
            .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // CONNECT/SUBSCRIBE dispatch to SimpleBrokerMessageHandler (which records the session and its
        // subscriptions) happens asynchronously on clientInboundChannel's executor, after send() already returned.
        Thread.sleep(1000);

        List<Message<?>> capturedOutbound = new CopyOnWriteArrayList<>();
        BlockingQueue<Message<?>> deliveredToAuthorized = new LinkedBlockingQueue<>();
        MessageHandler capture = message -> {
            capturedOutbound.add(message);
            deliveredToAuthorized.add(message);
        };
        clientOutboundChannel.subscribe(capture);
        try {
            TestAuthentication.authenticateAs(s.faculty.userId());
            var opened = (AttendanceSessionService.OpenResult.Success)
                attendanceSessionService.open(s.sectionId, s.meetingId, Instant.now().plus(1, ChronoUnit.HOURS));
            TestAuthentication.clear();
            var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "WS9S");
            day9Fixtures.enrollStudent(student, s.sectionId);
            TestAuthentication.authenticateAs(student.userId());
            attendanceRecordService.scan(opened.sessionId(), opened.rawToken());
            TestAuthentication.clear();

            Message<?> delivered = deliveredToAuthorized.poll(5, TimeUnit.SECONDS);
            assertThat(delivered).isNotNull();
            var deliveredAccessor = StompHeaderAccessor.wrap(delivered);
            assertThat(deliveredAccessor.getSessionId()).isEqualTo(authorizedSessionId);
            assertThat(capturedOutbound).noneMatch(m -> unauthorizedSessionId.equals(StompHeaderAccessor.wrap(m).getSessionId()));
        } finally {
            clientOutboundChannel.unsubscribe(capture);
        }
    }
}
