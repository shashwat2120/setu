package com.setu.identity.infrastructure.security;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Not run in CI ({@code @Disabled}) — a one-off benchmark harness, per the
 * Day 3 instruction to benchmark Argon2id parameters on real hardware rather
 * than copying published defaults. Re-run manually (remove {@code @Disabled}
 * temporarily) on production-representative hardware before going live; the
 * numbers baked into {@code Argon2Properties}' defaults were measured on the
 * dev machine this was built on and are a starting point, not a permanent
 * answer.
 * <p>
 * Actual measured run on this machine (5-hash average each, after JIT warmup):
 * <pre>
 * memory=19456KiB iterations=2 parallelism=1 -> 146ms/hash
 * memory=19456KiB iterations=3 parallelism=1 -> 156ms/hash
 * memory=47104KiB iterations=1 parallelism=1 -> 138ms/hash
 * memory=65536KiB iterations=2 parallelism=1 -> 403ms/hash  &lt;- chosen
 * memory=65536KiB iterations=3 parallelism=4 -> 551ms/hash
 * </pre>
 * Chose memory=64MiB, iterations=2, parallelism=1: lands in OWASP's
 * recommended ~300-500ms interactive-login range, uses well over OWASP's
 * 19MiB minimum memory cost (the dominant cost for GPU/ASIC cracking
 * resistance), and parallelism=1 avoids thread-pool contention under
 * concurrent login load on a shared app server — the parallelism=4 variant
 * bought little extra hashing cost per core actually used.
 */
@Disabled("manual benchmark, not a CI test")
class Argon2BenchmarkTests {

    @Test
    void benchmarkCandidateParameters() {
        int[][] candidates = {
            // {memoryKiB, iterations, parallelism}
            {19_456, 2, 1},
            {19_456, 3, 1},
            {47_104, 1, 1},
            {65_536, 2, 1},
            {65_536, 3, 4},
        };
        for (int[] c : candidates) {
            Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, c[2], c[0], c[1]);
            // warm up JIT
            encoder.encode("warmup-password");
            long start = System.nanoTime();
            int runs = 5;
            for (int i = 0; i < runs; i++) {
                encoder.encode("benchmark-password-" + i);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000 / runs;
            System.out.printf("memory=%dKiB iterations=%d parallelism=%d -> %dms/hash%n",
                c[0], c[1], c[2], elapsedMs);
        }
    }
}
