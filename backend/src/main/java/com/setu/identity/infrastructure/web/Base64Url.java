package com.setu.identity.infrastructure.web;

import java.util.Base64;

/** WebAuthn's own encoding convention for binary fields in JSON (challenge, credential id, user handle, public key). */
final class Base64Url {

    private Base64Url() {
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
