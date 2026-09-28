package com.setu.identity.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Thin wrapper around Spring Security's Argon2PasswordEncoder, configured with this project's own benchmarked parameters (see Argon2Properties). */
@Component
@EnableConfigurationProperties(Argon2Properties.class)
public class Argon2PasswordService {

    private final Argon2PasswordEncoder encoder;
    private final Argon2Properties properties;

    public Argon2PasswordService(Argon2Properties properties) {
        this.properties = properties;
        this.encoder = new Argon2PasswordEncoder(
            properties.getSaltLength(), properties.getHashLength(), properties.getParallelism(),
            properties.getMemoryKib(), properties.getIterations());
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** Constant-time verification — Argon2PasswordEncoder.matches() re-derives the hash and compares, never short-circuits on a byte mismatch. */
    public boolean verify(String rawPassword, String storedHash) {
        return encoder.matches(rawPassword, storedHash);
    }

    public Argon2Properties properties() {
        return properties;
    }
}
