package com.setu.identity.infrastructure.security;

import com.setu.shared.security.Jwks;
import com.setu.shared.security.JwtKeySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Identity service's signing key (EC P-256, ES256) and the public keys it still accepts and publishes.
 * <p>
 * <b>Rotation:</b> put the new private key in {@code app.security.jwt.signing-key} and the old PUBLIC key in
 * {@code app.security.jwt.previous-public-keys}. New tokens are signed with the new key; tokens signed with the old one (at most one access
 * token lifetime, 15 minutes) still verify, because the old key stays in the published JWKS. Remove it a little later.
 * <p>
 * With no signing key configured an ephemeral one is generated at start-up. That is fine for a single process (tests, local development) and
 * wrong for anything else: its tokens die with the process and no other service can verify them. It is logged as a warning every time.
 * Keys are configured as single-line Base64: the PKCS#8 DER of the private key, the X.509 DER of a public key
 * ({@code backend/tools/generate-jwt-key.js} prints both).
 */
@Component
public class JwtKeyStore implements JwtKeySource {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyStore.class);

    private final PrivateKey signingKey;
    private final String signingKeyId;
    /** Current key first, then the retired ones. */
    private final Map<String, PublicKey> publicKeys = new LinkedHashMap<>();

    public JwtKeyStore(JwtProperties properties) {
        try {
            KeyPair pair;
            if (properties.getSigningKey() == null || properties.getSigningKey().isBlank()) {
                log.warn("app.security.jwt.signing-key is not set: generated an EPHEMERAL signing key. Tokens will not survive a restart and "
                    + "other services cannot verify them. Set JWT_SIGNING_KEY (see backend/tools/generate-jwt-key.js) for anything but local development.");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                pair = generator.generateKeyPair();
            } else {
                KeyFactory factory = KeyFactory.getInstance("EC");
                PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(properties.getSigningKey().strip())));
                pair = new KeyPair(derivePublic(privateKey), privateKey);
            }
            this.signingKey = pair.getPrivate();
            this.signingKeyId = keyId(pair.getPublic());
            publicKeys.put(signingKeyId, pair.getPublic());
            for (String encoded : properties.getPreviousPublicKeys()) {
                if (!encoded.isBlank()) {
                    PublicKey retired = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(encoded.strip())));
                    publicKeys.putIfAbsent(keyId(retired), retired);
                }
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("app.security.jwt keys are not valid Base64 EC P-256 keys (private: PKCS#8, public: X.509): " + e.getMessage(), e);
        }
    }

    PrivateKey signingKey() {
        return signingKey;
    }

    public String signingKeyId() {
        return signingKeyId;
    }

    @Override
    public Optional<PublicKey> publicKey(String keyId) {
        return Optional.ofNullable(publicKeys.get(keyId));
    }

    /** The JWKS document served at {@code /.well-known/jwks.json}. */
    public String jwksJson() {
        return Jwks.toJson(publicKeys);
    }

    /** Key id = the first 22 Base64url characters of the SHA-256 of the public key, so the same key always has the same id. */
    static String keyId(PublicKey key) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
    }

    /** The public half of an EC private key, by multiplying the generator by the private scalar. */
    private static PublicKey derivePublic(PrivateKey privateKey) throws GeneralSecurityException {
        java.security.interfaces.ECPrivateKey ec = (java.security.interfaces.ECPrivateKey) privateKey;
        java.security.spec.ECParameterSpec params = ec.getParams();
        java.security.spec.ECPoint w = multiply(params.getGenerator(), ec.getS(), params);
        return KeyFactory.getInstance("EC").generatePublic(new java.security.spec.ECPublicKeySpec(w, params));
    }

    // --- minimal affine-coordinate EC point arithmetic (double-and-add) for deriving the public key from the private scalar ---

    private static java.security.spec.ECPoint multiply(java.security.spec.ECPoint p, java.math.BigInteger k, java.security.spec.ECParameterSpec params) {
        java.math.BigInteger prime = ((java.security.spec.ECFieldFp) params.getCurve().getField()).getP();
        java.math.BigInteger a = params.getCurve().getA();
        java.security.spec.ECPoint result = java.security.spec.ECPoint.POINT_INFINITY;
        java.security.spec.ECPoint addend = p;
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) {
                result = add(result, addend, a, prime);
            }
            addend = add(addend, addend, a, prime);
        }
        return result;
    }

    private static java.security.spec.ECPoint add(java.security.spec.ECPoint p, java.security.spec.ECPoint q, java.math.BigInteger a, java.math.BigInteger prime) {
        if (p.equals(java.security.spec.ECPoint.POINT_INFINITY)) {
            return q;
        }
        if (q.equals(java.security.spec.ECPoint.POINT_INFINITY)) {
            return p;
        }
        java.math.BigInteger lambda;
        if (p.getAffineX().equals(q.getAffineX())) {
            if (!p.getAffineY().equals(q.getAffineY()) || p.getAffineY().signum() == 0) {
                return java.security.spec.ECPoint.POINT_INFINITY;
            }
            lambda = p.getAffineX().pow(2).multiply(java.math.BigInteger.valueOf(3)).add(a)
                .multiply(p.getAffineY().shiftLeft(1).modInverse(prime)).mod(prime);
        } else {
            lambda = q.getAffineY().subtract(p.getAffineY()).multiply(q.getAffineX().subtract(p.getAffineX()).modInverse(prime)).mod(prime);
        }
        java.math.BigInteger x = lambda.pow(2).subtract(p.getAffineX()).subtract(q.getAffineX()).mod(prime);
        java.math.BigInteger y = lambda.multiply(p.getAffineX().subtract(x)).subtract(p.getAffineY()).mod(prime);
        return new java.security.spec.ECPoint(x, y);
    }
}
