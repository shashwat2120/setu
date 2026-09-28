package com.setu.identity.application;

import com.setu.identity.domain.User;
import com.setu.identity.infrastructure.UserRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Emergency user suspension — Day 4 instruction §8's named example of a
 * step-up-gated sensitive operation, and §9's "privileged administrative
 * actions" audit requirement. Deliberately narrow: it flips
 * identity.users.status, nothing else (it does not touch role grants,
 * sessions, or passkeys — revoking access those ways is a separate,
 * already-built mechanism: SessionService#revokeAllSessionsForUser).
 */
@Service
public class UserSuspensionService {

    private final UserRepository userRepository;
    private final AuthorizationContext authorizationContext;
    private final SessionService sessionService;
    private final AuditLogWriter auditLogWriter;

    public UserSuspensionService(UserRepository userRepository, AuthorizationContext authorizationContext,
                                  SessionService sessionService, AuditLogWriter auditLogWriter) {
        this.userRepository = userRepository;
        this.authorizationContext = authorizationContext;
        this.sessionService = sessionService;
        this.auditLogWriter = auditLogWriter;
    }

    public enum Result {SUSPENDED, USER_NOT_FOUND}

    @Transactional
    public Result suspend(UUID targetUserId, UUID tenantId, String reason) {
        authorizationContext.assertStepUp();
        authorizationContext.assertCan("SUSPEND_USER", ResourceRef.of("user", targetUserId, tenantId));

        Optional<User> maybeUser = userRepository.findById(targetUserId);
        if (maybeUser.isEmpty()) {
            return Result.USER_NOT_FOUND;
        }
        User user = maybeUser.get();
        user.suspend();
        userRepository.save(user);

        int revokedSessions = sessionService.revokeAllSessionsForUser(targetUserId, "ADMIN_SUSPENSION");

        UUID actorUserId = authorizationContext.currentUserId();
        auditLogWriter.write(tenantId, actorUserId, "USER_SUSPENDED", "identity.users", targetUserId,
            Map.of("reason", reason, "sessionsRevoked", revokedSessions));
        return Result.SUSPENDED;
    }
}
