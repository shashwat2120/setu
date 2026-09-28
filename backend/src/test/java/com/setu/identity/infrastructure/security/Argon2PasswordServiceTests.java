package com.setu.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2PasswordServiceTests {

    // Deliberately cheap parameters here (not the real benchmarked ones) so this test runs fast — the parameter *values* are covered by Argon2BenchmarkTests, this test is only about verify() correctness.
    private final Argon2Properties fastProperties = fastTestProperties();

    private static Argon2Properties fastTestProperties() {
        Argon2Properties properties = new Argon2Properties();
        properties.setMemoryKib(8);
        properties.setIterations(1);
        properties.setParallelism(1);
        return properties;
    }

    private final Argon2PasswordService service = new Argon2PasswordService(fastProperties);

    @Test
    void correctPasswordVerifiesSuccessfully() {
        String hash = service.hash("correct horse battery staple");
        assertThat(service.verify("correct horse battery staple", hash)).isTrue();
    }

    @Test
    void wrongPasswordFailsVerification() {
        String hash = service.hash("correct horse battery staple");
        assertThat(service.verify("wrong password", hash)).isFalse();
    }

    @Test
    void hashIsSaltedDifferentlyEachTime() {
        String hash1 = service.hash("same password");
        String hash2 = service.hash("same password");
        assertThat(hash1).isNotEqualTo(hash2);
        assertThat(service.verify("same password", hash1)).isTrue();
        assertThat(service.verify("same password", hash2)).isTrue();
    }

    @Test
    void hashIsTaggedArgon2id() {
        String hash = service.hash("whatever");
        assertThat(hash).startsWith("$argon2id$");
    }
}
