package com.setu.shared.security;

/** The token is not acceptable: forged, expired, for another issuer or audience, signed with an unknown or disallowed key, or malformed. */
public class TokenRejectedException extends Exception {

    public TokenRejectedException(String message) {
        super(message);
    }

    public TokenRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
