package com.setu.identity.application;

import com.setu.identity.domain.User;
import com.setu.identity.domain.UserDeactivatedEvent;
import com.setu.identity.domain.UserReactivatedEvent;
import com.setu.identity.domain.UserStatus;
import com.setu.identity.domain.UserSuspendedEvent;
import com.setu.identity.infrastructure.UserRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The account-level access lifecycle - deliberately separate from any
 * module's profile status (directory.StudentProfile.status,
 * directory.FacultyProfile.status, and so on). Profile status answers "what
 * is this person's standing in the academic/employment record"; this class
 * answers "can this login authenticate at all." The two are NOT wired
 * together: per docs/kafka-event-catalog.md's {@code StudentSuspended} row,
 * a student-status change is a grant-review trigger (not auto-revoke) even
 * for guardian access, and account suspension is listed separately there as
 * its own Identity-producer event with its own explicit trigger. A college
 * admin who wants withdrawing/suspending/terminating someone's profile to
 * ALSO cut off their login must call this service's operations too,
 * deliberately, not as an automatic side effect, so a WITHDRAWN student who
 * still needs to pay an outstanding balance, view a final transcript, or
 * appeal isn't silently locked out by an academic-records clerical action.
 * <p>
 * Was UserSuspensionService (Day 4 instruction section 8's named example of
 * a step-up-gated sensitive operation) - renamed and expanded here to cover
 * the full ACTIVE/SUSPENDED/DEACTIVATED lifecycle rather than just the
 * one-way suspend it started as.
 */
@Service
public class UserAccessService {

    private final UserRepository userRepository;
    private final AuthorizationContext authorizationContext;
    private final SessionService sessionService;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public UserAccessService(UserRepository userRepository, AuthorizationContext authorizationContext,
                              SessionService sessionService, AuditLogWriter auditLogWriter,
                              OutboxEventWriter outboxEventWriter) {
        this.userRepository = userRepository;
        this.authorizationContext = authorizationContext;
        this.sessionService = sessionService;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    public enum SuspendResult {SUSPENDED, USER_NOT_FOUND, ALREADY_SUSPENDED, ALREADY_DEACTIVATED}

    @Transactional
    public SuspendResult suspend(UUID targetUserId, UUID tenantId, String reason) {
        authorizationContext.assertStepUp();
        authorizationContext.assertCan("SUSPEND_USER", ResourceRef.of("user", targetUserId, tenantId));

        Optional<User> maybeUser = userRepository.findById(targetUserId);
        if (maybeUser.isEmpty()) {
            return SuspendResult.USER_NOT_FOUND;
        }
        User user = maybeUser.get();
        if (user.isDeactivated()) {
            return SuspendResult.ALREADY_DEACTIVATED;
        }
        if (user.isSuspended()) {
            return SuspendResult.ALREADY_SUSPENDED;
        }
        user.suspend();
        userRepository.save(user);

        int revokedSessions = sessionService.revokeAllSessionsForUser(targetUserId, "ADMIN_SUSPENSION");
        UUID actorUserId = authorizationContext.currentUserId();

        var event = new UserSuspendedEvent(UUID.randomUUID(), Instant.now(), targetUserId, UUID.randomUUID(), null,
            targetUserId, reason, actorUserId);
        outboxEventWriter.write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(),
            event, event.tenantId(), event.correlationId(), event.causationId());
        auditLogWriter.write(tenantId, actorUserId, "USER_SUSPENDED", "identity.users", targetUserId,
            Map.of("reason", reason == null ? "" : reason, "sessionsRevoked", revokedSessions));
        return SuspendResult.SUSPENDED;
    }

    public enum ReactivateResult {REACTIVATED, USER_NOT_FOUND, NOT_SUSPENDED}

    /** Reverses a suspension only — never valid from DEACTIVATED, which is deliberately terminal (see class javadoc). */
    @Transactional
    public ReactivateResult reactivate(UUID targetUserId, UUID tenantId, String reason) {
        authorizationContext.assertStepUp();
        authorizationContext.assertCan("SUSPEND_USER", ResourceRef.of("user", targetUserId, tenantId));

        Optional<User> maybeUser = userRepository.findById(targetUserId);
        if (maybeUser.isEmpty()) {
            return ReactivateResult.USER_NOT_FOUND;
        }
        User user = maybeUser.get();
        if (!user.isSuspended()) {
            return ReactivateResult.NOT_SUSPENDED;
        }
        user.reactivate();
        userRepository.save(user);
        UUID actorUserId = authorizationContext.currentUserId();

        var event = new UserReactivatedEvent(UUID.randomUUID(), Instant.now(), targetUserId, UUID.randomUUID(), null,
            targetUserId, reason, actorUserId);
        outboxEventWriter.write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(),
            event, event.tenantId(), event.correlationId(), event.causationId());
        auditLogWriter.write(tenantId, actorUserId, "USER_REACTIVATED", "identity.users", targetUserId,
            Map.of("reason", reason == null ? "" : reason));
        return ReactivateResult.REACTIVATED;
    }

    public enum DeactivateResult {DEACTIVATED, USER_NOT_FOUND, ALREADY_DEACTIVATED}

    /** Terminal offboarding — from ACTIVE or SUSPENDED, never reversible by {@link #reactivate}. */
    @Transactional
    public DeactivateResult deactivate(UUID targetUserId, UUID tenantId, String reason) {
        authorizationContext.assertStepUp();
        authorizationContext.assertCan("SUSPEND_USER", ResourceRef.of("user", targetUserId, tenantId));

        Optional<User> maybeUser = userRepository.findById(targetUserId);
        if (maybeUser.isEmpty()) {
            return DeactivateResult.USER_NOT_FOUND;
        }
        User user = maybeUser.get();
        if (user.isDeactivated()) {
            return DeactivateResult.ALREADY_DEACTIVATED;
        }
        user.deactivate();
        userRepository.save(user);

        int revokedSessions = sessionService.revokeAllSessionsForUser(targetUserId, "ADMIN_DEACTIVATION");
        UUID actorUserId = authorizationContext.currentUserId();

        var event = new UserDeactivatedEvent(UUID.randomUUID(), Instant.now(), targetUserId, UUID.randomUUID(), null,
            targetUserId, actorUserId, false);
        outboxEventWriter.write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(),
            event, event.tenantId(), event.correlationId(), event.causationId());
        auditLogWriter.write(tenantId, actorUserId, "USER_DEACTIVATED", "identity.users", targetUserId,
            Map.of("reason", reason == null ? "" : reason, "sessionsRevoked", revokedSessions));
        return DeactivateResult.DEACTIVATED;
    }

    /** Read-only lookup for the admin UI to know which action (suspend/reactivate/deactivate) applies — not step-up gated, same as any other permission-checked read. */
    @Transactional(readOnly = true)
    public Optional<UserStatus> status(UUID targetUserId, UUID tenantId) {
        authorizationContext.assertCan("SUSPEND_USER", ResourceRef.of("user", targetUserId, tenantId));
        return userRepository.findById(targetUserId).map(User::status);
    }
}
