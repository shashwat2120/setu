# Identity service contract (Sprint 6A.2)

What the Identity service promises to the gateway and to every other service, and what it expects from them. The first half is enforced by
tests that run today; the second half says what a consumer must do to be safe. Nothing here needs the services to be on separate machines:
the Identity service can already start on its own (`IdentityServiceApplication`), and the monolith issues and verifies exactly these tokens.

## 1. What Identity owns

Accounts, persons, passkeys, passwords, sessions and refresh tokens, roles, permissions, role assignments, and the authorization decision
(`AuthorizationService`). Tables: the `identity` schema (see [extraction-audit.md](../extraction-audit.md) section 5). No other service
reads or writes them.

## 2. Access tokens

| | |
|---|---|
| Format | JWT (RFC 7519), signed **ES256** (ECDSA P-256 / SHA-256) |
| Header | `alg: ES256`, `kid: <key id>` (required) |
| Claims | `iss` = `setu`, `aud` = `setu-api`, `sub` = user id (UUID), `jti`, `iat`, `exp` (15 minutes), `fam` = login (session family) id, `sup` = step-up epoch seconds (only right after a fresh passkey check) |
| Not in the token | tenant, roles, permissions. A role or tenant change must take effect at once, so they are resolved per request, never carried |
| Signing key | held only by Identity (`JWT_SIGNING_KEY`, PKCS#8 Base64). Nobody else holds a secret that can mint a token |
| Refresh | opaque, hashed at rest, rotated on every use, reuse revokes the whole login; cookie `setu_rt`, `Secure; HttpOnly; SameSite=Strict; Path=/auth` |

### Verifying a token (everyone except Identity)

`KeyedTokenVerifier` in the shared kernel is the reference implementation; a service that is not written in Java must do the same:

1. Split the token; read `kid` from the header. **No `kid`, or one not in the key set: reject.**
2. Accept only `alg = ES256`. **Reject `none`, any HMAC algorithm, and anything else**, whatever the header claims, so a token cannot be
   forged by signing with the public key as an HMAC secret.
3. Verify the signature with the public key for that `kid`.
4. Require `iss = setu`, `aud` containing `setu-api`, and `exp` in the future.
5. Check the revocation list (section 4). **If it cannot be read, reject.**

`RemoteTokenAuthenticator` is steps 1 to 5 together. A rejected token is an anonymous request, never a partially trusted one.

## 3. Key publication and rotation

`GET /.well-known/jwks.json` (public, no token) returns an RFC 7517 key set, `Content-Type: application/jwk-set+json`,
`Cache-Control: public, max-age=300`. Public parameters only (`kty EC`, `crv P-256`, `kid`, `use sig`, `alg ES256`, `x`, `y`); never `d`.
The key id is the first 22 characters of the Base64url SHA-256 of the public key.

Consumers (`JwksKeySource`):

- cache the set; **refetch once when an unknown `kid` arrives** (this is how a rotation is picked up with no restart), at most once per 30 s so
  made-up key ids cannot turn a service into a request amplifier against Identity;
- keep working with the keys already held when Identity is unreachable (stale-if-error), and refuse a key never seen;
- ignore anything in the document that is not an EC P-256 signing key with a `kid`, and **any point that is not on the curve** (the JDK does not
  check this; a hostile document could otherwise plant a weak key).

**Rotation:** `backend/tools/generate-jwt-key.js` makes a key pair. Set `JWT_SIGNING_KEY` to the new private key and put the old key's *public*
half in `JWT_PREVIOUS_PUBLIC_KEYS` (comma separated). New tokens use the new key; tokens signed by the old one verify for the rest of their
15 minutes because the old key is still published. Remove it after that. With no `JWT_SIGNING_KEY` Identity generates an ephemeral key and logs a
warning: acceptable for one local process, wrong for anything else.

## 4. Revocation

An access token is stateless for 15 minutes, which is too long after a logout, a suspension or a stolen refresh token. Identity therefore
publishes two kinds of fact to Redis (`LoginRevocations`), and every service reads them before trusting a signed, unexpired token:

| Key | Written when | Means |
|---|---|---|
| `setu:revoked:login:<fam>` | logout; refresh-token reuse detected | every token with that `fam` is dead |
| `setu:revoked:account:<userId>` = epoch seconds | suspension, deactivation, password reset (all sessions revoked) | every token for that user issued at or before that instant is dead (a later login is not caught) |

Keys expire after the access-token lifetime plus a minute. Redis is an acceleration, not the authority: Identity's own database is, and the
monolith still checks it directly (`LoginLivenessService`). Reads fail closed; Identity's writes are best effort and logged at ERROR if they
fail, because the token then lives out its 15 minutes.

## 5. Events Identity publishes (outbox → Kafka)

Topic `setu.<aggregate_type>`; envelope per [kafka-event-catalog.md](../kafka-event-catalog.md).

| Event | Aggregate | Tenant | Why a consumer wants it |
|---|---|---|---|
| `SessionRevoked` | `sessions` | none | audit; drop any cached login state |
| `UserSuspended`, `UserDeactivated`, `UserReactivated` | `users` | none | cut off or restore a person in a local read model |
| `UserRoleAssigned`, `UserRoleRevoked` | `user_roles` | the tenant, or none for a platform role | build the local read model of who holds which role, so a service authorizes without calling Identity per request |

`UserRoleAssigned`/`UserRoleRevoked` payload: `userRoleId, userId, roleCode, actorUserId` (null for bootstrap grants). `eventVersion` 1.

## 6. What Identity needs from others

Nothing at request time. The authorization engine asks a `GuardianDelegationChecker` for guardian grants (Academic owns them); inside the
Identity service that checker is absent and **delegation is denied, not assumed**. When Academic is its own service, the check becomes a call
to it (open decision in [extraction-audit.md](../extraction-audit.md) section 4).

## 7. Running it alone

`IdentityServiceApplication` (`com.setu.identity`) starts the identity module with only the shared kernel: it scans `com.setu.identity` and
`com.setu.shared` minus `shared.admin`, `shared.seed` and `shared.ai`, which exist to span modules; it excludes Spring Modulith's runtime
(there is only one module to describe) and does not run Flyway (the monolith's migrations own the schema until 6B.2 splits databases). It is
inert inside the monolith unless `setu.service=identity`. `IdentityServiceWiringTests` fails if any bean from another module appears.

## 8. Tests that hold this contract

| Contract | Test |
|---|---|
| ES256 only, `kid` required, issuer/audience/expiry, tamper, `alg=none`, HMAC-with-public-key confusion, unknown `kid`, rotation window | `KeyedTokenVerifierTests` |
| JWKS cache, refetch on unknown `kid`, rate limit, stale-if-error, no private parameters, off-curve and wrong-type keys skipped | `JwksKeySourceTests` |
| Key configuration, stable key id, rotation end to end, malformed config fails start-up | `JwtKeyStoreTests` |
| Public endpoint; a verifier that knows only the JWKS accepts Identity's tokens | `JwksEndpointTests` |
| Logout, reuse, suspension publish revocations; a later login is not caught; another user is not affected | `RevocationPublishingTests` |
| Signature + revocation, fail closed | `RemoteTokenAuthenticatorTests` |
| Only identity and kernel beans in the service context; delegation unavailable; keys published | `IdentityServiceWiringTests` |
| Role events written with the change | `RoleAssignmentServiceTests` |
| Tokens stop working at logout/suspension (the monolith's own check) | `AccessTokenRevocationTests` |
