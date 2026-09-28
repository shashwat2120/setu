package com.setu.identity.application;

import java.util.UUID;

/**
 * Everything the web layer needs to build a WebAuthn
 * PublicKeyCredentialCreationOptions response. {@code userHandle} is derived
 * from the user's own UUID (raw 16 bytes), never their email/username — the
 * WebAuthn user handle must be an opaque, non-PII identifier (the "secure
 * user_handle handling" requirement). {@code residentKey} is "required": a
 * discoverable credential is what makes a passkey a passkey — it's what lets
 * {@link PasskeyAuthenticationService#startAuthentication} support the
 * usernameless flow at all. Requesting it here is what a real client must
 * honor in its own PublicKeyCredentialCreationOptions.authenticatorSelection.
 */
public record RegistrationChallenge(UUID challengeId, byte[] challenge, byte[] userHandle, String rpId,
                                     String rpName, String userEmail, long timeoutMillis) {

    public String residentKey() {
        return "required";
    }
}
