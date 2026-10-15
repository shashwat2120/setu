package com.setu.shared.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole check a non-Identity service makes on a bearer token: a good signature is necessary but not sufficient. */
class RemoteTokenAuthenticatorTests {

    private static final class InMemoryRevocations implements LoginRevocations {
        final Set<UUID> logins = new HashSet<>();
        final Set<UUID> accounts = new HashSet<>();
        boolean unavailable;

        @Override
        public void revokeLogin(UUID familyId) {
            logins.add(familyId);
        }

        @Override
        public void revokeAccount(UUID userId) {
            accounts.add(userId);
        }

        @Override
        public boolean isRevoked(AccessTokenClaims claims) {
            return unavailable || logins.contains(claims.familyId()) || accounts.contains(claims.userId());
        }
    }

    private KeyPair pair;
    private InMemoryRevocations revocations;
    private RemoteTokenAuthenticator authenticator;
    private final UUID user = UUID.randomUUID();
    private final UUID family = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        pair = generator.generateKeyPair();
        revocations = new InMemoryRevocations();
        authenticator = new RemoteTokenAuthenticator(
            new KeyedTokenVerifier(id -> "k".equals(id) ? Optional.of(pair.getPublic()) : Optional.empty(), "setu", "setu-api"), revocations);
    }

    private String token() {
        return Jwts.builder().header().keyId("k").and()
            .subject(user.toString()).issuer("setu").audience().add("setu-api").and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id("j").claim("fam", family.toString())
            .signWith(pair.getPrivate(), Jwts.SIG.ES256).compact();
    }

    @Test
    void aGoodUnrevokedTokenAuthenticates() {
        assertThat(authenticator.authenticate(token())).hasValueSatisfying(c -> assertThat(c.userId()).isEqualTo(user));
    }

    @Test
    void aRevokedLoginIsRefusedEvenThoughTheSignatureIsFine() {
        revocations.revokeLogin(family);
        assertThat(authenticator.authenticate(token())).isEmpty();
    }

    @Test
    void aRevokedAccountIsRefused() {
        revocations.revokeAccount(user);
        assertThat(authenticator.authenticate(token())).isEmpty();
    }

    @Test
    void anUnavailableRevocationListFailsClosed() {
        revocations.unavailable = true;
        assertThat(authenticator.authenticate(token())).isEmpty();
    }

    @Test
    void forgedAndMalformedTokensNeverAuthenticate() {
        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("garbage")).isEmpty();
        String t = token();
        assertThat(authenticator.authenticate(t.substring(0, t.length() - 4) + "AAAA")).isEmpty();
    }
}
