package com.setu.identity.application;

import com.setu.identity.domain.Session;
import com.setu.identity.infrastructure.SessionRepository;
import com.setu.identity.infrastructure.security.RefreshTokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against the real Postgres, matching MigrationIntegrationTests' pattern. Per docs/transaction-concurrency-matrix.md scenario 7. */
@SpringBootTest
@Transactional
class SessionServiceTests {

    @Autowired
    SessionService sessionService;
    @Autowired
    SessionRepository sessionRepository;
    @Autowired
    RefreshTokenHasher tokenHasher;
    @Autowired
    UserRegistrationService registrationService;

    private UUID newUser() {
        var outcome = (UserRegistrationService.Outcome.Created)
            registrationService.register("session-" + UUID.randomUUID() + "@example.com");
        return outcome.userId();
    }

    @Test
    void freshSessionCanBeRotated() {
        UUID userId = newUser();
        TokenPair initial = sessionService.startNewSession(userId, null);

        var outcome = sessionService.refresh(initial.refreshToken(), null);

        assertThat(outcome).isInstanceOf(RefreshOutcome.Rotated.class);
        var rotated = ((RefreshOutcome.Rotated) outcome).tokens();
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(rotated.accessToken()).isNotBlank();
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        UUID userId = newUser();
        String rawToken = tokenHasher.generateRawToken();
        Instant now = Instant.now();
        Session expiredSession = Session.startNewFamily(UUID.randomUUID(), userId, tokenHasher.hash(rawToken),
            now.minus(1, ChronoUnit.DAYS), null, now.minus(31, ChronoUnit.DAYS));
        sessionRepository.save(expiredSession);

        var outcome = sessionService.refresh(rawToken, null);

        assertThat(outcome).isInstanceOf(RefreshOutcome.Invalid.class);
    }

    @Test
    void reusingAnAlreadyRotatedTokenRevokesTheWholeFamily() {
        UUID userId = newUser();
        TokenPair initial = sessionService.startNewSession(userId, null);
        String originalRawToken = initial.refreshToken();

        // Legitimate rotation — the original token is now stale.
        var firstRefresh = sessionService.refresh(originalRawToken, null);
        assertThat(firstRefresh).isInstanceOf(RefreshOutcome.Rotated.class);
        String rotatedRawToken = ((RefreshOutcome.Rotated) firstRefresh).tokens().refreshToken();

        // Replaying the ORIGINAL (already-rotated-away) token is treated as compromise.
        var reuseAttempt = sessionService.refresh(originalRawToken, null);
        assertThat(reuseAttempt).isInstanceOf(RefreshOutcome.ReuseDetected.class);

        // The entire family — including the legitimately rotated-to token — is now dead.
        var rotatedTokenAfterCompromise = sessionService.refresh(rotatedRawToken, null);
        assertThat(rotatedTokenAfterCompromise).isInstanceOf(RefreshOutcome.ReuseDetected.class);
    }

    @Test
    void revokedSessionCannotBeRefreshed() {
        UUID userId = newUser();
        TokenPair initial = sessionService.startNewSession(userId, null);

        boolean loggedOut = sessionService.logout(initial.refreshToken());
        assertThat(loggedOut).isTrue();

        var outcome = sessionService.refresh(initial.refreshToken(), null);
        assertThat(outcome).isInstanceOf(RefreshOutcome.ReuseDetected.class);
    }

    @Test
    void logoutRevokesOnlyThePresentedSession_notOtherDevices() {
        UUID userId = newUser();
        TokenPair deviceA = sessionService.startNewSession(userId, null);
        TokenPair deviceB = sessionService.startNewSession(userId, null);

        sessionService.logout(deviceA.refreshToken());

        var deviceARefresh = sessionService.refresh(deviceA.refreshToken(), null);
        assertThat(deviceARefresh).isInstanceOf(RefreshOutcome.ReuseDetected.class);

        var deviceBRefresh = sessionService.refresh(deviceB.refreshToken(), null);
        assertThat(deviceBRefresh).isInstanceOf(RefreshOutcome.Rotated.class);
    }

    @Test
    void logoutOfUnknownTokenReturnsFalseRatherThanThrowing() {
        boolean result = sessionService.logout("not-a-real-token");
        assertThat(result).isFalse();
    }
}
