package com.setu.identity.application;

import com.setu.identity.domain.Session;
import com.setu.identity.domain.SessionRevokedEvent;
import com.setu.identity.infrastructure.SessionRepository;
import com.setu.identity.infrastructure.UserRepository;
import com.setu.identity.infrastructure.security.JwtService;
import com.setu.identity.infrastructure.security.JwtProperties;
import com.setu.identity.infrastructure.security.RefreshTokenHasher;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues/rotates/revokes sessions per docs/transaction-concurrency-matrix.md
 * scenario 7. Every method here that touches identity.sessions runs in one
 * transaction, and reuse detection always completes (family revoked) before
 * any new token is ever handed back — fail closed, matching the doc's
 * explicit transaction-boundary note.
 */
@Service
@EnableConfigurationProperties(JwtProperties.class)
public class SessionService {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenHasher tokenHasher;
    private final OutboxEventWriter outboxEventWriter;

    public SessionService(SessionRepository sessionRepository, UserRepository userRepository, JwtService jwtService,
                           JwtProperties jwtProperties, RefreshTokenHasher tokenHasher,
                           OutboxEventWriter outboxEventWriter) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.tokenHasher = tokenHasher;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public TokenPair startNewSession(UUID userId, String deviceMetadataJson) {
        Instant now = Instant.now();
        String rawRefreshToken = tokenHasher.generateRawToken();
        Instant refreshExpiry = now.plus(jwtProperties.getRefreshTokenTtlDays(), ChronoUnit.DAYS);
        Session session = Session.startNewFamily(
            UUID.randomUUID(), userId, tokenHasher.hash(rawRefreshToken), refreshExpiry, deviceMetadataJson, now);
        sessionRepository.save(session);
        return new TokenPair(jwtService.issueAccessToken(userId), rawRefreshToken, refreshExpiry, session.id());
    }

    @Transactional
    public RefreshOutcome refresh(String rawRefreshToken, String deviceMetadataJson) {
        Instant now = Instant.now();
        String hash = tokenHasher.hash(rawRefreshToken);
        Optional<Session> found = sessionRepository.findByRefreshTokenHash(hash);
        if (found.isEmpty()) {
            return new RefreshOutcome.Invalid("unknown refresh token");
        }
        Session session = found.get();
        if (session.isRevoked()) {
            int revokedCount = sessionRepository.revokeFamily(session.familyId(), now);
            publishSessionRevoked(session.familyId(), session.userId(), "REFRESH_TOKEN_REUSE_DETECTED", now);
            return new RefreshOutcome.ReuseDetected(
                "refresh token was already used once; " + revokedCount + " session(s) in the family revoked");
        }
        if (session.isExpired(now)) {
            return new RefreshOutcome.Invalid("refresh token expired");
        }
        // Defense in depth: UserAccessService#suspend/#deactivate already revoke every session for a user in the
        // same call, so this should never actually find a live session for a non-ACTIVE account — but a refresh
        // is itself a re-authentication, and this is the cheapest possible place to make that guarantee absolute
        // rather than relying solely on the revocation having already run to completion.
        var user = userRepository.findById(session.userId());
        if (user.isPresent() && !user.get().isActive()) {
            session.revoke(now);
            sessionRepository.save(session);
            return new RefreshOutcome.Invalid("account is " + user.get().status().name().toLowerCase());
        }
        session.revoke(now);
        sessionRepository.save(session);
        String newRawRefreshToken = tokenHasher.generateRawToken();
        Instant refreshExpiry = now.plus(jwtProperties.getRefreshTokenTtlDays(), ChronoUnit.DAYS);
        Session rotated = Session.rotateWithinFamily(UUID.randomUUID(), session.userId(), session.familyId(),
            tokenHasher.hash(newRawRefreshToken), refreshExpiry, deviceMetadataJson, now);
        sessionRepository.save(rotated);
        return new RefreshOutcome.Rotated(new TokenPair(
            jwtService.issueAccessToken(session.userId()), newRawRefreshToken, refreshExpiry, rotated.id()));
    }

    /** Single-device logout: revokes only the presented session, not the whole family. */
    @Transactional
    public boolean logout(String rawRefreshToken) {
        Instant now = Instant.now();
        Optional<Session> found = sessionRepository.findByRefreshTokenHash(tokenHasher.hash(rawRefreshToken));
        if (found.isEmpty() || found.get().isRevoked()) {
            return false;
        }
        Session session = found.get();
        session.revoke(now);
        sessionRepository.save(session);
        publishSessionRevokedSingle(session.id(), session.userId(), "LOGOUT", now);
        return true;
    }

    /** Emergency suspension support: revoke every session a user holds, across every family. */
    @Transactional
    public int revokeAllSessionsForUser(UUID userId, String reason) {
        Instant now = Instant.now();
        var sessions = sessionRepository.findByUserIdAndRevokedAtIsNull(userId);
        for (Session s : sessions) {
            s.revoke(now);
            sessionRepository.save(s);
            publishSessionRevokedSingle(s.id(), userId, reason, now);
        }
        return sessions.size();
    }

    private void publishSessionRevoked(UUID familyId, UUID userId, String reason, Instant now) {
        var event = new SessionRevokedEvent(UUID.randomUUID(), now, familyId, UUID.randomUUID(), null,
            familyId, userId, reason);
        outboxEventWriter.write("sessions", familyId, event.eventType(), event.eventVersion(), event,
            event.tenantId(), event.correlationId(), event.causationId());
    }

    private void publishSessionRevokedSingle(UUID sessionId, UUID userId, String reason, Instant now) {
        var event = new SessionRevokedEvent(UUID.randomUUID(), now, sessionId, UUID.randomUUID(), null,
            sessionId, userId, reason);
        outboxEventWriter.write("sessions", sessionId, event.eventType(), event.eventVersion(), event,
            event.tenantId(), event.correlationId(), event.causationId());
    }
}
