package com.setu.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * Token verification for every process that is not Identity: Identity's published keys ({@link JwksKeySource}), the strict ES256 verifier,
 * and the check that also consults the revocation list ({@link RemoteTokenAuthenticator}). The gateway uses these beans on their own; the
 * other services get them together with their security filter chain ({@link RemoteAuthenticationConfig}). Active only when
 * {@code setu.auth.mode=remote}.
 */
@Configuration
@ConditionalOnProperty(name = "setu.auth.mode", havingValue = "remote")
public class RemoteVerificationConfig {

    @Bean
    JwksKeySource jwksKeySource(@Value("${app.security.jwt.jwks-uri:http://localhost:8081/.well-known/jwks.json}") URI jwksUri,
                                @Value("${app.security.jwt.jwks-refresh-minutes:10}") long refreshMinutes,
                                @Value("${app.security.jwt.jwks-min-refetch-seconds:30}") long minRefetchSeconds) {
        return new JwksKeySource(new HttpJwksFetcher(jwksUri, Duration.ofSeconds(3)), Clock.systemUTC(),
            Duration.ofMinutes(refreshMinutes), Duration.ofSeconds(minRefetchSeconds));
    }

    @Bean
    TokenVerifier tokenVerifier(JwksKeySource keys, @Value("${app.security.jwt.issuer:setu}") String issuer,
                                @Value("${app.security.jwt.audience:setu-api}") String audience) {
        return new KeyedTokenVerifier(keys, issuer, audience);
    }

    @Bean
    RemoteTokenAuthenticator remoteTokenAuthenticator(TokenVerifier verifier, LoginRevocations revocations) {
        return new RemoteTokenAuthenticator(verifier, revocations);
    }

}
