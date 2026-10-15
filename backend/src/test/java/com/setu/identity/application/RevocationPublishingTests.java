package com.setu.identity.application;

import com.setu.identity.infrastructure.security.JwtService;
import com.setu.shared.security.AccessTokenClaims;
import com.setu.shared.security.LoginRevocations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of immediate revocation: when Identity ends a login it also tells the revocation list the other services read, so a
 * service that never touches Identity's database stops accepting the token at once, not 15 minutes later. Against the real Redis.
 */
@SpringBootTest
class RevocationPublishingTests {

    @Autowired
    SessionService sessionService;
    @Autowired
    JwtService jwtService;
    @Autowired
    LoginRevocations revocations;
    @Autowired
    UserRegistrationService registrationService;

    private UUID newUser() {
        return ((UserRegistrationService.Outcome.Created) registrationService.register("rev-" + UUID.randomUUID() + "@example.com")).userId();
    }

    private AccessTokenClaims claims(String token) throws Exception {
        return jwtService.validate(token);
    }

    @Test
    void aFreshLoginIsNotRevoked() throws Exception {
        var login = sessionService.startNewSession(newUser(), null);
        assertThat(revocations.isRevoked(claims(login.accessToken()))).isFalse();
    }

    @Test
    void logoutRevokesTheLoginForEveryService() throws Exception {
        var login = sessionService.startNewSession(newUser(), null);
        AccessTokenClaims before = claims(login.accessToken());

        assertThat(sessionService.logout(login.refreshToken())).isTrue();

        assertThat(revocations.isRevoked(before)).isTrue();
    }

    @Test
    void refreshTokenReuseRevokesTheWholeLogin() throws Exception {
        var login = sessionService.startNewSession(newUser(), null);
        var rotated = ((RefreshOutcome.Rotated) sessionService.refresh(login.refreshToken(), null)).tokens();
        assertThat(revocations.isRevoked(claims(rotated.accessToken()))).isFalse();

        assertThat(sessionService.refresh(login.refreshToken(), null)).isInstanceOf(RefreshOutcome.ReuseDetected.class);

        assertThat(revocations.isRevoked(claims(rotated.accessToken()))).isTrue();
    }

    @Test
    void revokingAnAccountKillsItsTokensButNotAnotherUsers() throws Exception {
        UUID victim = newUser();
        UUID bystander = newUser();
        var victimLogin = sessionService.startNewSession(victim, null);
        var bystanderLogin = sessionService.startNewSession(bystander, null);
        Thread.sleep(1100);   // the account revocation is second-granular: tokens issued before it are dead

        sessionService.revokeAllSessionsForUser(victim, "TEST");

        assertThat(revocations.isRevoked(claims(victimLogin.accessToken()))).isTrue();
        assertThat(revocations.isRevoked(claims(bystanderLogin.accessToken()))).isFalse();
    }

    @Test
    void aLoginAfterTheRevocationIsNotCaughtByIt() throws Exception {
        UUID user = newUser();
        sessionService.startNewSession(user, null);
        sessionService.revokeAllSessionsForUser(user, "TEST");
        Thread.sleep(1100);

        var again = sessionService.startNewSession(user, null);

        assertThat(revocations.isRevoked(claims(again.accessToken()))).isFalse();
    }
}
