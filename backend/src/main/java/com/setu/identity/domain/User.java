package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Maps identity.users (V5, +password columns V51) exactly — see docs/erd/*.md. */
@Entity
@Table(name = "users", schema = "identity")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    @SuppressWarnings("unused") // mapped column: written by JPA, not read in Java yet
    private String phone;

    @Column(columnDefinition = "citext")
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "password_algo")
    private PasswordAlgorithm passwordAlgo;

    @Column(name = "password_updated_at")
    private Instant passwordUpdatedAt;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Version
    private int version;

    protected User() {
    }

    public static User register(UUID id, String email, Instant now) {
        User user = new User();
        user.id = id;
        user.email = email;
        user.status = UserStatus.ACTIVE;
        user.createdAt = now;
        user.updatedAt = now;
        user.failedLoginAttempts = 0;
        return user;
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public UserStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return status == UserStatus.SUSPENDED;
    }

    public boolean isDeactivated() {
        return status == UserStatus.DEACTIVATED;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
        this.updatedAt = Instant.now();
    }

    /** Reverses a suspension. Never valid from DEACTIVATED — deactivation is deliberately terminal; the caller (UserAccessService) enforces that, this method only performs the transition. */
    public void reactivate() {
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /** Terminal: no operation on this class ever moves a user out of DEACTIVATED. Offboarding, not a reversible hold. */
    public void deactivate() {
        this.status = UserStatus.DEACTIVATED;
        this.updatedAt = Instant.now();
    }

    public String passwordHash() {
        return passwordHash;
    }

    /** Called with an already-hashed value — this class never hashes, that's Argon2PasswordService's job (infrastructure). */
    public void setPasswordHash(String argon2Hash, Instant now) {
        this.passwordHash = argon2Hash;
        this.passwordAlgo = PasswordAlgorithm.ARGON2ID;
        this.passwordUpdatedAt = now;
        this.updatedAt = now;
    }

    /** True once locked_until is in the future. Passkey login is never gated by this — see PasskeyAuthenticationService. */
    public boolean isPasswordLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void recordFailedPasswordAttempt(Instant now, int maxAttempts, long lockoutMinutes) {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = now.plus(lockoutMinutes, ChronoUnit.MINUTES);
        }
        this.updatedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public int failedLoginAttempts() {
        return failedLoginAttempts;
    }
}
