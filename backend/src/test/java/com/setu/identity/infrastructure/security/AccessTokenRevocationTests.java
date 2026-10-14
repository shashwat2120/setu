package com.setu.identity.infrastructure.security;

import com.setu.identity.application.RefreshOutcome;
import com.setu.identity.application.SessionService;
import com.setu.identity.application.TokenAuthenticationService;
import com.setu.identity.application.TokenPair;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Canonical spec §13 (Realtime: "revoked session blocked") and §6: an access token must stop working as soon as the login it came from
 * is over, not only when its 15 minutes run out. Ending a login (logout, suspension, refresh-token reuse) revokes its sessions, and the
 * access token is bound to the login's session family, so every request and every STOMP CONNECT re-checks that the login is still live.
 * <p>
 * The probe endpoint is {@code GET /notifications}, which any signed-in person may call.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessTokenRevocationTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    SessionService sessionService;
    @Autowired
    UserRegistrationService registrationService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JwtService jwtService;
    @Autowired
    TokenAuthenticationService tokenAuthenticationService;

    private UUID newUser() {
        var outcome = (UserRegistrationService.Outcome.Created)
            registrationService.register("revoke-" + UUID.randomUUID() + "@example.com");
        return outcome.userId();
    }

    private int probe(String accessToken) throws Exception {
        return mockMvc.perform(get("/notifications").header("Authorization", "Bearer " + accessToken)).andReturn().getResponse().getStatus();
    }

    @Test
    void aLiveLoginsTokenWorks() throws Exception {
        TokenPair login = sessionService.startNewSession(newUser(), null);

        assertThat(probe(login.accessToken())).isEqualTo(200);
        assertThat(tokenAuthenticationService.resolveUserId(login.accessToken())).isPresent();
    }

    @Test
    void loggingOutBlocksTheAccessTokenAtOnceOverHttpAndStomp() throws Exception {
        TokenPair login = sessionService.startNewSession(newUser(), null);

        assertThat(sessionService.logout(login.refreshToken())).isTrue();

        assertThat(probe(login.accessToken())).isEqualTo(401);
        assertThat(tokenAuthenticationService.resolveUserId(login.accessToken())).isEmpty();
    }

    @Test
    void revokingEverySessionOfAUserBlocksTheirTokens() throws Exception {
        UUID userId = newUser();
        TokenPair laptop = sessionService.startNewSession(userId, null);
        TokenPair phone = sessionService.startNewSession(userId, null);

        sessionService.revokeAllSessionsForUser(userId, "TEST");

        assertThat(probe(laptop.accessToken())).isEqualTo(401);
        assertThat(probe(phone.accessToken())).isEqualTo(401);
        assertThat(tokenAuthenticationService.resolveUserId(phone.accessToken())).isEmpty();
    }

    @Test
    void loggingOutOneDeviceLeavesTheOtherDevicesTokenWorking() throws Exception {
        UUID userId = newUser();
        TokenPair laptop = sessionService.startNewSession(userId, null);
        TokenPair phone = sessionService.startNewSession(userId, null);

        sessionService.logout(laptop.refreshToken());

        assertThat(probe(laptop.accessToken())).isEqualTo(401);
        assertThat(probe(phone.accessToken())).isEqualTo(200);
    }

    @Test
    void aSuspendedOrDeactivatedAccountsTokenIsBlockedEvenIfItsSessionsWereNotRevoked() throws Exception {
        UUID suspended = newUser();
        UUID deactivated = newUser();
        TokenPair a = sessionService.startNewSession(suspended, null);
        TokenPair b = sessionService.startNewSession(deactivated, null);

        var user = userRepository.findById(suspended).orElseThrow();
        user.suspend();
        userRepository.save(user);
        var other = userRepository.findById(deactivated).orElseThrow();
        other.deactivate();
        userRepository.save(other);

        assertThat(probe(a.accessToken())).isEqualTo(401);
        assertThat(probe(b.accessToken())).isEqualTo(401);
        assertThat(tokenAuthenticationService.resolveUserId(a.accessToken())).isEmpty();
    }

    @Test
    void rotatingTheRefreshTokenKeepsTheEarlierAccessTokenWorkingBecauseItIsTheSameLogin() throws Exception {
        TokenPair login = sessionService.startNewSession(newUser(), null);

        var rotated = ((RefreshOutcome.Rotated) sessionService.refresh(login.refreshToken(), null)).tokens();

        assertThat(probe(rotated.accessToken())).isEqualTo(200);
        assertThat(probe(login.accessToken())).as("an in-flight request signed before the rotation").isEqualTo(200);
    }

    @Test
    void replayingAnAlreadyUsedRefreshTokenBlocksEveryTokenOfThatLogin() throws Exception {
        TokenPair login = sessionService.startNewSession(newUser(), null);
        var rotated = ((RefreshOutcome.Rotated) sessionService.refresh(login.refreshToken(), null)).tokens();

        assertThat(sessionService.refresh(login.refreshToken(), null)).isInstanceOf(RefreshOutcome.ReuseDetected.class);

        assertThat(probe(rotated.accessToken())).isEqualTo(401);
        assertThat(probe(login.accessToken())).isEqualTo(401);
    }

    @Test
    void everyTokenIssuedAtLoginAndRefreshIsBoundToItsLogin() {
        TokenPair login = sessionService.startNewSession(newUser(), null);
        var rotated = ((RefreshOutcome.Rotated) sessionService.refresh(login.refreshToken(), null)).tokens();

        var first = jwtService.validate(login.accessToken());
        var second = jwtService.validate(rotated.accessToken());

        assertThat(first.familyId()).isNotNull();
        assertThat(second.familyId()).isEqualTo(first.familyId());
    }

    @Test
    void aTokenWithNoLoginBindingIsStillAcceptedOnlyForTheTestHelpersThatIssueOne() throws Exception {
        // Tokens minted directly by JwtService (tests, tooling) carry no login binding and are checked only for the account's status.
        UUID userId = newUser();
        assertThat(probe(jwtService.issueAccessToken(userId))).isEqualTo(200);

        var user = userRepository.findById(userId).orElseThrow();
        user.suspend();
        userRepository.save(user);
        assertThat(probe(jwtService.issueAccessToken(userId))).as("but a suspended account is blocked regardless").isEqualTo(401);
    }

    @Test
    void anUnknownUserIdInAValidlySignedTokenIsRefused() throws Exception {
        assertThat(probe(jwtService.issueAccessToken(UUID.randomUUID()))).isEqualTo(401);
    }
}
