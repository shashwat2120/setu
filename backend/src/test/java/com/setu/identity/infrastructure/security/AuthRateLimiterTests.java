package com.setu.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every address/email in here is freshly random so counters from other tests (or an earlier run still inside its
 * Redis window) can never interfere. The per-IP ceiling is shared by every MockMvc request on 127.0.0.1, so the
 * HTTP tests only exercise the per-email limit; the per-IP limit is covered at the limiter level with a unique IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRateLimiterTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    StringRedisTemplate redisTemplate;

    private static String uniqueEmail() {
        return "ratelimit-" + UUID.randomUUID() + "@example.test";
    }

    private static String uniqueIp() {
        return "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    private AuthRateLimiter limiter(int loginPerEmail, int loginPerIp, int resetPerEmail, int resetPerIp) {
        return new AuthRateLimiter(redisTemplate, loginPerEmail, loginPerIp, 60, resetPerEmail, resetPerIp, 900);
    }

    // ---- limiter ----

    @Test
    void emailLimitBlocksTheNextAttemptAndReportsRetryAfter() {
        var limiter = limiter(3, 1000, 3, 1000);
        String email = uniqueEmail();
        String ip = uniqueIp();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.checkLogin(email, ip).allowed()).isTrue();
        }
        var blocked = limiter.checkLogin(email, ip);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void ipLimitBlocksADifferentEmailFromTheSameAddress() {
        var limiter = limiter(1000, 3, 1000, 3);
        String ip = uniqueIp();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.checkLogin(uniqueEmail(), ip).allowed()).isTrue();
        }

        // a brand-new email, but the address has used up its allowance (password spraying)
        assertThat(limiter.checkLogin(uniqueEmail(), ip).allowed()).isFalse();
        // while another address is unaffected
        assertThat(limiter.checkLogin(uniqueEmail(), uniqueIp()).allowed()).isTrue();
    }

    @Test
    void emailIsNormalizedSoCaseAndWhitespaceDoNotEvadeTheLimit() {
        var limiter = limiter(2, 1000, 2, 1000);
        String base = uniqueEmail();
        String ip = uniqueIp();

        assertThat(limiter.checkLogin(base, ip).allowed()).isTrue();
        assertThat(limiter.checkLogin(" " + base.toUpperCase() + " ", ip).allowed()).isTrue();
        assertThat(limiter.checkLogin(base, ip).allowed()).isFalse();
    }

    @Test
    void onlyTheFirstRefusedRequestInAWindowIsMarkedForAuditing() {
        var limiter = limiter(1, 1000, 1, 1000);
        String email = uniqueEmail();
        String ip = uniqueIp();

        limiter.checkLogin(email, ip);
        var first = limiter.checkLogin(email, ip);
        var second = limiter.checkLogin(email, ip);

        assertThat(first.firstBlock()).isTrue();
        assertThat(second.allowed()).isFalse();
        assertThat(second.firstBlock()).isFalse();
    }

    @Test
    void loginAndPasswordResetCountSeparately() {
        var limiter = limiter(1, 1000, 1, 1000);
        String email = uniqueEmail();
        String ip = uniqueIp();

        assertThat(limiter.checkLogin(email, ip).allowed()).isTrue();
        assertThat(limiter.checkLogin(email, ip).allowed()).isFalse();
        assertThat(limiter.checkPasswordReset(email, ip).allowed()).isTrue();
    }

    @Test
    void redisKeysNeverContainTheRawEmail() {
        var limiter = limiter(5, 5, 5, 5);
        String email = uniqueEmail();

        limiter.checkLogin(email, uniqueIp());

        var keys = redisTemplate.keys("auth:login:email:*");
        assertThat(keys).isNotEmpty();
        assertThat(keys).noneMatch(k -> k.contains(email) || k.contains("@"));
    }

    // ---- HTTP ----

    @Test
    void loginIsRefusedWith429AfterTenAttemptsForTheSameEmail() throws Exception {
        String email = uniqueEmail(); // unknown account: every attempt is a plain 401 until the limiter steps in
        String body = "{\"email\":\"" + email + "\",\"password\":\"wrong-password-1\"}";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"));

        // a different email is not affected
        String other = "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"wrong-password-1\"}";
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(other))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetIsRefusedAfterThreeRequestsAndAnswersIdenticallyUntilThen() throws Exception {
        String body = "{\"email\":\"" + uniqueEmail() + "\"}";

        for (int i = 0; i < 3; i++) {
            // unknown email: same 200 a real account would get — no enumeration
            mockMvc.perform(post("/auth/password/reset/initiate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        }
        mockMvc.perform(post("/auth/password/reset/initiate").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.message").exists());
    }
}
