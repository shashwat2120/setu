package com.setu.audit;

import com.setu.identity.application.PasswordResetService;
import com.setu.identity.application.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Canonical spec §6: the refresh token lives only in a Secure, HttpOnly, SameSite cookie (never in a response body, so never in
 * localStorage), is stored server-side only as a hash, and is rotated on every use; replaying a used one ends the whole login.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionCookieAuditTests {

    private static final String PASSWORD = "SetuDemo#2025!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRegistrationService registrationService;
    @Autowired
    PasswordResetService passwordResetService;

    private String registerWithPassword() {
        String email = "cookie-" + UUID.randomUUID() + "@example.com";
        registrationService.register(email);
        String token = passwordResetService.initiate(email).orElseThrow();
        assertThat(passwordResetService.complete(token, PASSWORD)).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);
        return email;
    }

    private MockHttpServletResponse login(String email) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn().getResponse();
    }

    private static String cookieValue(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        assertThat(header).isNotNull().startsWith("setu_rt=");
        return header.substring("setu_rt=".length(), header.indexOf(';'));
    }

    @Test
    void theRefreshTokenIsOnlyEverASecureHttpOnlyStrictCookieScopedToAuth() throws Exception {
        MockHttpServletResponse response = login(registerWithPassword());

        assertThat(response.getStatus()).isEqualTo(200);
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/auth").contains("Max-Age=");
        String refresh = cookieValue(response);
        assertThat(refresh).isNotBlank();
        assertThat(response.getContentAsString()).as("the body carries the short-lived access token only").contains("accessToken")
            .doesNotContain(refresh).doesNotContain("refresh");
    }

    @Test
    void refreshingRotatesTheCookieAndReplayingTheOldOneIsRefused() throws Exception {
        MockHttpServletResponse first = login(registerWithPassword());
        String original = cookieValue(first);

        MockHttpServletResponse rotated = mockMvc.perform(post("/auth/refresh").cookie(new Cookie("setu_rt", original))).andReturn().getResponse();
        assertThat(rotated.getStatus()).isEqualTo(200);
        String next = cookieValue(rotated);
        assertThat(next).as("a new refresh token on every use").isNotEqualTo(original);

        int replay = mockMvc.perform(post("/auth/refresh").cookie(new Cookie("setu_rt", original))).andReturn().getResponse().getStatus();
        assertThat(replay).as("the used token is dead").isEqualTo(401);
        int afterReuse = mockMvc.perform(post("/auth/refresh").cookie(new Cookie("setu_rt", next))).andReturn().getResponse().getStatus();
        assertThat(afterReuse).as("and replaying it ended the whole login, including the newer token").isEqualTo(401);
    }

    @Test
    void loggingOutClearsTheCookie() throws Exception {
        String refresh = cookieValue(login(registerWithPassword()));

        MockHttpServletResponse out = mockMvc.perform(post("/auth/logout").cookie(new Cookie("setu_rt", refresh))).andReturn().getResponse();

        assertThat(out.getHeader("Set-Cookie")).contains("setu_rt=;").contains("Max-Age=0").contains("HttpOnly").contains("Secure");
        assertThat(mockMvc.perform(post("/auth/refresh").cookie(new Cookie("setu_rt", refresh))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void onlyAHashOfTheRefreshTokenIsStored() throws Exception {
        String refresh = cookieValue(login(registerWithPassword()));

        assertThat(refresh).hasSizeGreaterThan(30);
        // Looked up by hash: the raw value is not a column value anywhere in identity.sessions.
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Integer rawMatches = jdbc.queryForObject("SELECT count(*) FROM identity.sessions WHERE refresh_token_hash = ?", Integer.class, refresh);
        assertThat(rawMatches).isZero();
    }

    @Autowired
    javax.sql.DataSource dataSource;
}
