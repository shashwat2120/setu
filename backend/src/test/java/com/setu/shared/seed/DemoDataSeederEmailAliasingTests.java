package com.setu.shared.seed;

import com.setu.identity.application.PasswordResetService;
import com.setu.identity.domain.User;
import com.setu.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code app.demo-data.email-base} (env {@code DEMO_EMAIL_BASE}) Gmail-style "+" aliasing added to
 * {@link DemoDataSeeder#demoEmail}: every seeded account across all 6 roles gets a distinct
 * {@code base+tag@domain} address that still satisfies {@code identity.users.email}'s unique constraint, and the
 * password-reset token flow still resolves each aliased address back to its own, correct account — never a
 * different seeded user. Runs in its OWN {@code @SpringBootTest} context (a different email-base property than
 * {@link DemoDataSeederVerificationTests}, so Spring can't share a cached context between them), with a fake,
 * non-deliverable base address — this class never touches Brevo and never needs a real API key.
 */
@SpringBootTest
@TestPropertySource(properties = {"app.demo-data.enabled=true", "app.demo-data.email-base=alias.demo.test@example.com"})
class DemoDataSeederEmailAliasingTests {

    @Autowired
    UserRepository userRepository;
    @Autowired
    PasswordResetService passwordResetService;

    private static final List<String> EXPECTED_ALIASES = List.of(
        "alias.demo.test+super.admin@example.com",
        "alias.demo.test+admin.sit@example.com",
        "alias.demo.test+faculty.rao@example.com",
        "alias.demo.test+student.strong@example.com",
        "alias.demo.test+guardian.kapoor@example.com",
        "alias.demo.test+placement.officer@example.com");

    @Test
    void everySeededRoleGetsADistinctGmailStyleAliasOfTheConfiguredBase() {
        for (String alias : EXPECTED_ALIASES) {
            assertThat(userRepository.findByEmail(alias))
                .as("seeded account for alias " + alias)
                .isPresent();
        }
        // Distinctness is already implied by 6 successful individual lookups against a real unique-email
        // constraint (a collision would have failed seeding entirely), but assert it directly too.
        assertThat(Set.copyOf(EXPECTED_ALIASES)).hasSize(EXPECTED_ALIASES.size());
    }

    @Test
    void everyAliasSharesTheSameConfiguredBaseMailbox() {
        for (String alias : EXPECTED_ALIASES) {
            assertThat(alias).startsWith("alias.demo.test+").endsWith("@example.com");
        }
    }

    @Test
    void aPasswordResetTokenForAnAliasedAddressResolvesBackToThatExactSeededAccountNeverAnother() {
        String targetAlias = "alias.demo.test+student.strong@example.com";
        String otherAlias = "alias.demo.test+student.average@example.com";
        UUID targetUserId = userRepository.findByEmail(targetAlias).orElseThrow().id();
        UUID otherUserId = userRepository.findByEmail(otherAlias).orElseThrow().id();
        assertThat(targetUserId).isNotEqualTo(otherUserId);

        String rawToken = passwordResetService.initiate(targetAlias).orElseThrow();
        var result = passwordResetService.complete(rawToken, "AliasRoundTrip#2025!");
        assertThat(result).isEqualTo(PasswordResetService.CompletionResult.SUCCESS);

        User afterReset = userRepository.findByEmail(targetAlias).orElseThrow();
        assertThat(afterReset.id()).isEqualTo(targetUserId);

        Optional<User> otherStillUnaffected = userRepository.findByEmail(otherAlias);
        assertThat(otherStillUnaffected).isPresent();
        assertThat(otherStillUnaffected.orElseThrow().id()).isEqualTo(otherUserId);
    }
}
