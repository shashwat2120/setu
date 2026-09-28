package com.setu.identity.application;

import com.setu.identity.domain.User;
import com.setu.identity.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal account creation — a prerequisite for Day 3's passkey/password
 * flows (a credential has to attach to some user row), not a full identity
 * lifecycle. No email-verification flow, no persons/directory linkage —
 * those belong to identity's broader scope, not Day 3's authentication
 * foundation.
 * <p>
 * {@code @NamedInterface} added Day 15 §demo-data: {@code
 * shared.seed.DemoDataSeeder} needs to create every seeded account through
 * the SAME real path a genuine self-registration would use — never a raw
 * {@code UserRepository} insert. No authorization check exists here (never
 * did — self-registration is inherently unauthenticated), so this is safe
 * to call from a bootstrap context with no signed-in principal.
 */
@org.springframework.modulith.NamedInterface
@Service
public class UserRegistrationService {

    private final UserRepository userRepository;

    public UserRegistrationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public sealed interface Outcome {
        record Created(UUID userId) implements Outcome {
        }

        record EmailAlreadyRegistered() implements Outcome {
        }
    }

    @Transactional
    public Outcome register(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            return new Outcome.EmailAlreadyRegistered();
        }
        User user = User.register(UUID.randomUUID(), email, Instant.now());
        userRepository.save(user);
        return new Outcome.Created(user.id());
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #register}. */
    @Transactional
    public UUID registerForSeed(String email) {
        return switch (register(email)) {
            case Outcome.Created c -> c.userId();
            case Outcome.EmailAlreadyRegistered ignored ->
                throw new IllegalStateException("demo account already exists: " + email + " (re-run against a clean database)");
        };
    }

    /**
     * Flat, cross-module-safe unwrap of {@link #register} for real (non-seed) orchestration callers outside this
     * module, e.g. {@code shared.admin.StudentAdmissionService}. {@code Outcome}'s nested {@code Created}/
     * {@code EmailAlreadyRegistered} records are NOT visible outside {@code identity} even though this class is
     * {@code @NamedInterface} (Spring Modulith only exposes a {@code @NamedInterface} class's own directly
     * declared members, never types nested inside a sealed interface it returns — the same rule
     * {@code AssessmentAccessSupport}'s own javadoc already documents for this codebase) — {@link Optional}
     * avoids referencing any such nested type at all.
     */
    @Transactional
    public Optional<UUID> registerIfNew(String email) {
        return switch (register(email)) {
            case Outcome.Created c -> Optional.of(c.userId());
            case Outcome.EmailAlreadyRegistered ignored -> Optional.empty();
        };
    }

}
