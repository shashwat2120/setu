package com.setu.identity.infrastructure.security;

/** Wraps webauthn4j's VerificationException so callers/tests depend on this project's own exception type, not a third-party library's, across the module boundary. Covers ceremony-level failures: wrong challenge, wrong RP ID, wrong origin, bad signature, sign-count regression. */
public class PasskeyVerificationException extends RuntimeException {

    public PasskeyVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
