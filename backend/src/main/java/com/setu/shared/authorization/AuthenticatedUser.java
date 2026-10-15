package com.setu.shared.authorization;

import java.time.Instant;
import java.util.UUID;

/** What the engine needs to know about the caller: who they are, and when they last passed a fresh passkey check. */
public interface AuthenticatedUser {

    UUID userId();

    /** Null unless the access token was issued right after a fresh passkey reauthentication. */
    Instant stepUpAt();
}
