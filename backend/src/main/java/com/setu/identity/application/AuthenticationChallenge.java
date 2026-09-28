package com.setu.identity.application;

import java.util.List;
import java.util.UUID;

/** allowCredentialIds is empty for a usernameless/discoverable-credential ceremony — the authenticator picks which passkey to use and reports it back via userHandle. */
public record AuthenticationChallenge(UUID challengeId, byte[] challenge, String rpId,
                                       List<byte[]> allowCredentialIds, long timeoutMillis) {
}
