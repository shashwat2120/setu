package com.setu.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON Web Key Set format (RFC 7517) for ES256 / P-256 public keys: how the Identity service publishes its keys and how other services
 * read them. Only public parameters are ever written, and only EC P-256 signing keys are ever accepted, so a malformed or hostile document
 * cannot introduce a different key type.
 */
public final class Jwks {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final int COORDINATE_BYTES = 32;

    private Jwks() {
    }

    /** The document for these keys, newest first: {@code {"keys":[{"kty":"EC","crv":"P-256","kid":...,"use":"sig","alg":"ES256","x":...,"y":...}]}}. */
    public static String toJson(Map<String, PublicKey> keysById) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode keys = root.putArray("keys");
        keysById.forEach((kid, key) -> {
            ECPublicKey ec = (ECPublicKey) key;
            ObjectNode jwk = keys.addObject();
            jwk.put("kty", "EC").put("crv", "P-256").put("kid", kid).put("use", "sig").put("alg", KeyedTokenVerifier.ALGORITHM);
            jwk.put("x", B64.encodeToString(fixedLength(ec.getW().getAffineX())));
            jwk.put("y", B64.encodeToString(fixedLength(ec.getW().getAffineY())));
        });
        return root.toString();
    }

    /** The usable keys in a document, by key id. Anything that is not an EC P-256 signing key with a key id is skipped, not trusted. */
    public static Map<String, PublicKey> parse(String json) {
        Map<String, PublicKey> out = new LinkedHashMap<>();
        try {
            JsonNode keys = MAPPER.readTree(json).path("keys");
            if (!keys.isArray()) {
                return out;
            }
            ECParameterSpec p256 = p256();
            KeyFactory factory = KeyFactory.getInstance("EC");
            for (JsonNode jwk : keys) {
                String kid = jwk.path("kid").asText("");
                boolean signingKey = !jwk.has("use") || "sig".equals(jwk.path("use").asText());
                boolean algOk = !jwk.has("alg") || KeyedTokenVerifier.ALGORITHM.equals(jwk.path("alg").asText());
                if (kid.isBlank() || !"EC".equals(jwk.path("kty").asText()) || !"P-256".equals(jwk.path("crv").asText()) || !signingKey || !algOk) {
                    continue;
                }
                try {
                    BigInteger x = new BigInteger(1, B64D.decode(jwk.path("x").asText()));
                    BigInteger y = new BigInteger(1, B64D.decode(jwk.path("y").asText()));
                    if (!onCurve(x, y, p256)) {
                        continue;   // the JDK does not check this itself; an off-curve point is the invalid-curve attack
                    }
                    out.put(kid, factory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256)));
                } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                    // a point that is not on the curve, or bad encoding: skip this key, keep the rest
                }
            }
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** y^2 = x^3 + ax + b (mod p), with both coordinates in [0, p). */
    private static boolean onCurve(BigInteger x, BigInteger y, ECParameterSpec spec) {
        BigInteger p = ((java.security.spec.ECFieldFp) spec.getCurve().getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(p);
        return left.equals(right);
    }

    private static ECParameterSpec p256() throws java.security.GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] fixedLength(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[COORDINATE_BYTES];
        int copy = Math.min(raw.length, COORDINATE_BYTES);
        System.arraycopy(raw, raw.length - copy, out, COORDINATE_BYTES - copy, copy);
        return out;
    }
}
