package com.setu.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Argon2id cost parameters. Defaults are NOT copied from Spring Security's
 * own {@code Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()} or from
 * an OWASP cheat sheet table blindly — they're the result of benchmarking on
 * this project's own dev hardware; see {@code Argon2BenchmarkTests}'
 * javadoc for the measured run and reasoning. Override via
 * {@code app.security.argon2.*} once production hardware is known, and
 * re-benchmark there rather than assuming these numbers transfer.
 */
@ConfigurationProperties(prefix = "app.security.argon2")
public class Argon2Properties {

    /** KiB. 64 MiB — see Argon2BenchmarkTests for why. */
    private int memoryKib = 65_536;

    /** Argon2id time cost. */
    private int iterations = 2;

    /** Argon2id parallelism (lanes). */
    private int parallelism = 1;

    private int saltLength = 16;

    private int hashLength = 32;

    /** Consecutive failed attempts before an account is locked. */
    private int maxFailedAttempts = 5;

    /** How long an account stays locked once maxFailedAttempts is reached. */
    private long lockoutMinutes = 15;

    public int getMemoryKib() {
        return memoryKib;
    }

    public void setMemoryKib(int memoryKib) {
        this.memoryKib = memoryKib;
    }

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public int getSaltLength() {
        return saltLength;
    }

    public void setSaltLength(int saltLength) {
        this.saltLength = saltLength;
    }

    public int getHashLength() {
        return hashLength;
    }

    public void setHashLength(int hashLength) {
        this.hashLength = hashLength;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public long getLockoutMinutes() {
        return lockoutMinutes;
    }

    public void setLockoutMinutes(long lockoutMinutes) {
        this.lockoutMinutes = lockoutMinutes;
    }
}
