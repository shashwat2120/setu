package com.setu.shared.authorization;

import java.util.UUID;

/**
 * {@code can(principal, permission, resource, action)} — the one
 * authorization entry point every module's application-layer services call
 * before performing a sensitive read or write (Day 4 instruction §10, §11).
 * The principal is read from the current security context, the same way
 * {@link com.setu.shared.tenant.TenantContext} reads tenant — callers never
 * pass a principal explicitly, so there's no way to accidentally check
 * authorization for the wrong (or an attacker-supplied) user id.
 */
public interface AuthorizationContext {

    AccessDecision can(String permissionCode, ResourceRef resource);

    /** Throws AccessDeniedException instead of returning Deny — for the common "authorize or abort" call shape in application services. */
    void assertCan(String permissionCode, ResourceRef resource);

    /**
     * True only if the currently authenticated principal's access token
     * carries a recent step-up (fresh passkey reauthentication) claim — see
     * docs/architecture-notes/authorization-engine.md §Step-up. Sensitive
     * operations (Day 4 instruction §8) must check this explicitly; it is
     * NOT folded into {@link #can} because most permission checks must NOT
     * require step-up, and conflating the two would make it too easy to
     * accidentally gate an ordinary read behind reauthentication.
     */
    boolean hasRecentStepUp();

    void assertStepUp();

    UUID currentUserId();

    /**
     * Day 9 §8 addition: {@code can}/{@code assertCan} always check the
     * CURRENT request's principal (from {@code SecurityContextHolder}) —
     * correct for every HTTP-facing call, but STOMP/WebSocket message
     * processing runs on the message broker's own threads, which never
     * populate a request-scoped security context. A caller that has
     * already authenticated a userId some other way (e.g. a STOMP CONNECT
     * frame's bearer token) needs to check a permission for THAT specific,
     * already-resolved user — not "whoever the current thread happens to
     * be." Implementations must resolve permissions/roles/tenant exactly
     * as {@link #can} does, just for the given id instead of the current
     * principal, and must NOT mutate any shared/thread-local security
     * state to do so.
     */
    AccessDecision canAsUser(UUID userId, String permissionCode, ResourceRef resource);
}
