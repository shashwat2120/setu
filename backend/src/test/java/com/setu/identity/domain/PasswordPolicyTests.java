package com.setu.identity.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The server-side rules for a new password (the reset page's own minLength is only a browser hint). */
class PasswordPolicyTests {

    @ParameterizedTest
    @ValueSource(strings = {"SetuDemo#2025!", "correct horse battery staple", "12 random words are fine", "ünïcödé-pässwörd-ok", "abcdefgh"})
    void acceptsReasonablePasswords(String password) {
        assertThat(PasswordPolicy.violation(password, "student@example.com")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "1234567", "Passw0r"})
    void rejectsTooShort(String password) {
        assertThat(PasswordPolicy.violation(password, "a@example.com")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "PASSWORD", "Password123", "12345678", "qwertyuiop", "iloveyou", "Welcome1"})
    void rejectsVeryCommonPasswordsInAnyCase(String password) {
        assertThat(PasswordPolicy.violation(password, "a@example.com")).hasValueSatisfying(v -> assertThat(v).contains("common"));
    }

    @Test
    void rejectsTheAccountsOwnEmailOrItsLocalPart() {
        assertThat(PasswordPolicy.violation("student.strong@demo.setu.local", "student.strong@demo.setu.local")).isPresent();
        assertThat(PasswordPolicy.violation("Student.Strong", "student.strong@demo.setu.local")).isPresent();
        // a different password that merely contains it is fine
        assertThat(PasswordPolicy.violation("student.strong-and-more!", "student.strong@demo.setu.local")).isEmpty();
    }

    @Test
    void rejectsNullAndOverlongPasswords() {
        assertThat(PasswordPolicy.violation(null, "a@example.com")).isPresent();
        assertThat(PasswordPolicy.violation("x".repeat(PasswordPolicy.MAX_LENGTH), "a@example.com")).isEmpty();
        assertThat(PasswordPolicy.violation("x".repeat(PasswordPolicy.MAX_LENGTH + 1), "a@example.com")).isPresent();
    }
}
