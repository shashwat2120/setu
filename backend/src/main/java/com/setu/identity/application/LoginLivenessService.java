package com.setu.identity.application;

import com.setu.identity.infrastructure.SessionRepository;
import com.setu.identity.infrastructure.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Whether a validly signed, unexpired access token may still be used: its account must exist and be ACTIVE, and, for a token issued to
 * a real login, that login (session family) must still have a live session.
 * <p>
 * Access tokens last 15 minutes and are otherwise stateless, so without this check a person who logged out, was suspended or had their
 * refresh token stolen and replayed would keep working access for the rest of that window, over HTTP and over STOMP alike. Roles and
 * tenant are still resolved fresh on every request elsewhere; this is the missing "is the login itself still valid" half. Two indexed
 * lookups per request. Fails closed: a database error makes the token unusable rather than trusted.
 */
@Service
public class LoginLivenessService {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public LoginLivenessService(SessionRepository sessionRepository, UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /** @param familyId the login the token is bound to; null for tokens minted directly by tests and tooling, which are checked for account status only */
    public boolean isLive(UUID userId, UUID familyId) {
        try {
            boolean active = userRepository.findById(userId).map(u -> u.isActive()).orElse(false);
            if (!active) {
                return false;
            }
            return familyId == null || sessionRepository.existsLiveSession(familyId, userId, Instant.now());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
