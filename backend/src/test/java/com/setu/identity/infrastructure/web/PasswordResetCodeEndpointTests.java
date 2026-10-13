package com.setu.identity.infrastructure.web;

import com.setu.identity.application.PasswordResetService;
import com.setu.identity.application.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of "forgot password with a code": what a person's browser sees. Every address is freshly random so the Redis rate
 * limit counters of other tests can never interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetCodeEndpointTests {

    private static final String GOOD_PASSWORD = "AGoodNewPassword#7";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PasswordResetService passwordResetService;
    @Autowired
    UserRegistrationService userRegistrationService;

    private String registerUser() {
        String email = "reset-code-http-" + UUID.randomUUID() + "@example.com";
        userRegistrationService.register(email);
        return email;
    }

    private static String confirmBody(String email, String code, String newPassword) {
        return "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    @Test
    void requestingACodeAnswersTheSameForAKnownAndAnUnknownEmailAndExposesNoCode() throws Exception {
        String known = registerUser();
        String unknown = "nobody-" + UUID.randomUUID() + "@example.com";

        for (String email : new String[]{known, unknown}) {
            mockMvc.perform(post("/auth/password/reset/initiate").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devOnlyResetCode").doesNotExist());
        }
    }

    @Test
    void theRightCodeAndAGoodPasswordAnswer204AndThePasswordWorksAtLogin() throws Exception {
        String email = registerUser();
        String code = passwordResetService.initiateAndEmail(email).orElseThrow();

        mockMvc.perform(post("/auth/password/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(email, code, GOOD_PASSWORD)))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + GOOD_PASSWORD + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void aWrongCodeAndAnUnknownEmailGiveTheSameAnswer() throws Exception {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);

        mockMvc.perform(post("/auth/password/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(email, "000000", GOOD_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("invalid or expired code"));
        mockMvc.perform(post("/auth/password/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody("nobody-" + UUID.randomUUID() + "@example.com", "000000", GOOD_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("invalid or expired code"));
    }

    @Test
    void aWeakPasswordGetsItsOwnMessageAndKeepsTheCodeUsable() throws Exception {
        String email = registerUser();
        String code = passwordResetService.initiateAndEmail(email).orElseThrow();

        mockMvc.perform(post("/auth/password/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(email, code, "short")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", startsWith("password_too_weak")));

        mockMvc.perform(post("/auth/password/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(email, code, GOOD_PASSWORD)))
            .andExpect(status().isNoContent());
    }

    @Test
    void theOldLinkEndpointIsGone() throws Exception {
        mockMvc.perform(post("/auth/password/reset/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"anything\",\"newPassword\":\"" + GOOD_PASSWORD + "\"}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void enteringCodesIsRefusedWith429AfterTenAttemptsForTheSameEmail() throws Exception {
        String email = registerUser();
        passwordResetService.initiateAndEmail(email);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/auth/password/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                    .content(confirmBody(email, "000000", GOOD_PASSWORD)))
                .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/auth/password/reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody(email, "000000", GOOD_PASSWORD)))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.message", containsString("too many attempts")));
    }
}
