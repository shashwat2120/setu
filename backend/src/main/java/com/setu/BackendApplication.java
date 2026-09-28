package com.setu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Excludes UserDetailsServiceAutoConfiguration: this project's identity
 * module (Sprint 5B Day 3) owns its own JWT/passkey/Argon2id authentication
 * end to end and never uses Spring Security's UserDetailsService/
 * AuthenticationManager — without this exclusion, Boot still generates an
 * unused default in-memory user (and its "generated security password" log
 * warning) on every startup because that autoconfiguration only backs off
 * when a UserDetailsService/AuthenticationProvider/AuthenticationManager
 * bean exists, not merely a SecurityFilterChain.
 * <p>
 * {@code @EnableScheduling} added Day 15 — the single {@code @Scheduled}
 * consumer is {@code shared.governance.OutboxPublisher}.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
