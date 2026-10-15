package com.setu.identity.infrastructure.security;

import com.setu.shared.security.Jwks;
import com.setu.shared.security.KeyedTokenVerifier;
import com.setu.shared.security.TokenRejectedException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Key configuration and rotation for the token issuer: a configured key survives restarts, retired keys keep verifying until removed. */
class JwtKeyStoreTests {

    private static KeyPair newPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String privateB64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }

    private static String publicB64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static JwtProperties properties(String signingKey, List<String> previous) {
        JwtProperties properties = new JwtProperties();
        properties.setSigningKey(signingKey);
        properties.setPreviousPublicKeys(previous);
        return properties;
    }

    @Test
    void aConfiguredKeyYieldsTheSameKeyIdAndPublicKeyEveryTime() throws Exception {
        KeyPair pair = newPair();
        JwtKeyStore first = new JwtKeyStore(properties(privateB64(pair), List.of()));
        JwtKeyStore second = new JwtKeyStore(properties(privateB64(pair), List.of()));

        assertThat(first.signingKeyId()).isEqualTo(second.signingKeyId());
        // The public key is derived from the private scalar, and must be the real one.
        assertThat(first.publicKey(first.signingKeyId())).contains(pair.getPublic());
    }

    @Test
    void withoutAConfiguredKeyAnEphemeralOneIsGeneratedAndStillWorks() {
        JwtKeyStore store = new JwtKeyStore(properties("", List.of()));
        JwtService service = new JwtService(new JwtProperties(), store);
        UUID user = UUID.randomUUID();
        assertThat(service.issueAccessToken(user)).isNotBlank();
    }

    @Test
    void rotationKeepsTokensFromTheRetiredKeyValidUntilItIsRemoved() throws Exception {
        KeyPair oldPair = newPair();
        KeyPair newPair = newPair();
        UUID user = UUID.randomUUID();
        JwtProperties beforeRotation = properties(privateB64(oldPair), List.of());
        String oldToken = new JwtService(beforeRotation, new JwtKeyStore(beforeRotation)).issueAccessToken(user);

        JwtProperties during = properties(privateB64(newPair), List.of(publicB64(oldPair)));
        JwtService rotating = new JwtService(during, new JwtKeyStore(during));
        assertThat(rotating.validate(oldToken).userId()).isEqualTo(user);                         // the old token still verifies
        assertThat(rotating.validate(rotating.issueAccessToken(user)).userId()).isEqualTo(user);   // new tokens use the new key
        assertThat(Jwks.parse(new JwtKeyStore(during).jwksJson())).hasSize(2);

        JwtProperties after = properties(privateB64(newPair), List.of());
        JwtService finished = new JwtService(after, new JwtKeyStore(after));
        assertThatThrownBy(() -> finished.validate(oldToken)).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void thePublishedKeySetContainsNoPrivateMaterial() throws Exception {
        KeyPair pair = newPair();
        String json = new JwtKeyStore(properties(privateB64(pair), List.of(publicB64(newPair())))).jwksJson();
        assertThat(json).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"");
        assertThat(Jwks.parse(json)).hasSize(2);
    }

    @Test
    void aTokenFromAnotherIssuersKeyIsRejected() throws Exception {
        JwtProperties mine = properties(privateB64(newPair()), List.of());
        JwtProperties theirs = properties(privateB64(newPair()), List.of());
        String foreign = new JwtService(theirs, new JwtKeyStore(theirs)).issueAccessToken(UUID.randomUUID());
        JwtService service = new JwtService(mine, new JwtKeyStore(mine));
        assertThatThrownBy(() -> service.validate(foreign)).isInstanceOf(TokenRejectedException.class);
    }

    @Test
    void malformedKeyConfigurationFailsStartUpInsteadOfSigningWithSomethingElse() {
        assertThatThrownBy(() -> new JwtKeyStore(properties("this-is-not-a-key", List.of())))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtKeyStore(properties("", List.of("###"))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issuedTokensUseEs256AndCarryTheKeyId() throws Exception {
        JwtProperties properties = properties(privateB64(newPair()), List.of());
        JwtKeyStore store = new JwtKeyStore(properties);
        String token = new JwtService(properties, store).issueAccessToken(UUID.randomUUID());
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"" + KeyedTokenVerifier.ALGORITHM + "\"").contains("\"kid\":\"" + store.signingKeyId() + "\"");
    }
}
