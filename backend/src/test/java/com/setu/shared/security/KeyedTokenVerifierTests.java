package com.setu.shared.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules every service applies to an access token: ES256 only, a known key id, the right issuer and audience, not expired. Each
 * case below is a way a forged or stale token could try to get through.
 */
class KeyedTokenVerifierTests {

    private static final String ISSUER = "setu";
    private static final String AUDIENCE = "setu-api";

    private KeyPair current;
    private KeyPair other;
    private KeyedTokenVerifier verifier;
    private final UUID user = UUID.randomUUID();
    private final UUID family = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        current = newPair();
        other = newPair();
        Map<String, PublicKey> keys = Map.of("kid-current", current.getPublic());
        verifier = new KeyedTokenVerifier(id -> Optional.ofNullable(keys.get(id)), ISSUER, AUDIENCE);
    }

    private static KeyPair newPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private io.jsonwebtoken.JwtBuilder base(String kid) {
        var builder = Jwts.builder()
            .subject(user.toString()).issuer(ISSUER).audience().add(AUDIENCE).and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600)))
            .id(UUID.randomUUID().toString());
        if (kid != null) {
            builder.header().keyId(kid);
        }
        return builder;
    }

    @Test
    void acceptsAValidTokenAndReadsItsClaims() throws Exception {
        Instant stepUp = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String token = base("kid-current").claim("fam", family.toString()).claim("sup", stepUp.getEpochSecond())
            .signWith(current.getPrivate(), Jwts.SIG.ES256).compact();

        AccessTokenClaims claims = verifier.verify(token);

        assertThat(claims.userId()).isEqualTo(user);
        assertThat(claims.familyId()).isEqualTo(family);
        assertThat(claims.stepUpAt()).isEqualTo(stepUp);
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    void familyAndStepUpAreOptional() throws Exception {
        AccessTokenClaims claims = verifier.verify(base("kid-current").signWith(current.getPrivate(), Jwts.SIG.ES256).compact());
        assertThat(claims.familyId()).isNull();
        assertThat(claims.stepUpAt()).isNull();
    }

    @Test
    void rejectsATokenSignedByAnotherKey() {
        String token = base("kid-current").signWith(other.getPrivate(), Jwts.SIG.ES256).compact();
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void rejectsATamperedPayload() {
        String token = base("kid-current").signWith(current.getPrivate(), Jwts.SIG.ES256).compact();
        String[] parts = token.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
            new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace(user.toString(), UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verify(parts[0] + "." + forged + "." + parts[2])).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = Jwts.builder().header().keyId("kid-current").and()
            .subject(user.toString()).issuer(ISSUER).audience().add(AUDIENCE).and()
            .issuedAt(Date.from(Instant.now().minusSeconds(1200))).expiration(Date.from(Instant.now().minusSeconds(600)))
            .id("x").signWith(current.getPrivate(), Jwts.SIG.ES256).compact();
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void rejectsTheWrongIssuerAndTheWrongAudience() {
        String wrongIssuer = Jwts.builder().header().keyId("kid-current").and()
            .subject(user.toString()).issuer("someone-else").audience().add(AUDIENCE).and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id("x")
            .signWith(current.getPrivate(), Jwts.SIG.ES256).compact();
        String wrongAudience = Jwts.builder().header().keyId("kid-current").and()
            .subject(user.toString()).issuer(ISSUER).audience().add("another-api").and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id("x")
            .signWith(current.getPrivate(), Jwts.SIG.ES256).compact();
        assertThatThrownBy(() -> verifier.verify(wrongIssuer)).isInstanceOf(TokenRejectedException.class);
        assertThatThrownBy(() -> verifier.verify(wrongAudience)).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void rejectsAnUnsignedAlgNoneToken() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"kid\":\"kid-current\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("{\"sub\":\"" + user + "\",\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"exp\":" + Instant.now().plusSeconds(600).getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verify(header + "." + payload + ".")).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void rejectsAnHmacTokenKeyedWithThePublicKey() {
        // The classic algorithm-confusion attack: sign HS256 using the (public) verification key bytes as the HMAC secret.
        byte[] publicBytes = current.getPublic().getEncoded();
        SecretKey hmac = Keys.hmacShaKeyFor(java.util.Arrays.copyOf(publicBytes, Math.max(32, publicBytes.length)));
        String token = base("kid-current").signWith(hmac, Jwts.SIG.HS256).compact();
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void rejectsATokenWithNoKeyIdOrAnUnknownOne() {
        String noKid = base(null).signWith(current.getPrivate(), Jwts.SIG.ES256).compact();
        String unknownKid = base("kid-unknown").signWith(current.getPrivate(), Jwts.SIG.ES256).compact();
        assertThatThrownBy(() -> verifier.verify(noKid)).isInstanceOf(TokenRejectedException.class);
        assertThatThrownBy(() -> verifier.verify(unknownKid)).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void rejectsBlankAndGarbage() {
        assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(TokenRejectedException.class);
        assertThatThrownBy(() -> verifier.verify("")).isInstanceOf(TokenRejectedException.class);
        assertThatThrownBy(() -> verifier.verify("not.a.jwt")).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void aRetiredKeyKeepsVerifyingWhileItIsStillListed() throws Exception {
        KeyPair retired = newPair();
        Map<String, PublicKey> keys = Map.of("kid-current", current.getPublic(), "kid-retired", retired.getPublic());
        KeyedTokenVerifier rotating = new KeyedTokenVerifier(id -> Optional.ofNullable(keys.get(id)), ISSUER, AUDIENCE);
        String signedByRetired = base("kid-retired").signWith(retired.getPrivate(), Jwts.SIG.ES256).compact();
        assertThat(rotating.verify(signedByRetired).userId()).isEqualTo(user);
        // ...and stops once it is dropped from the set.
        assertThatThrownBy(() -> verifier.verify(signedByRetired)).isInstanceOf(TokenRejectedException.class);
    }
}
