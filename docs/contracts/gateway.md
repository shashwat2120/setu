# API gateway contract (Sprint 6A.4)

The gateway is the one public address of the platform: browsers and other clients talk to it, never to a service. It authenticates at the
edge, limits traffic, picks a reachable instance and forwards. It has no database, no business code and no Spring Security; it is
`com.setu.gateway.GatewayApplication` (port 8080), inert inside the monolith unless `setu.service=gateway`.

## 1. Why it is not Spring Cloud Gateway

The work breakdown names Spring Cloud Gateway. No Spring Cloud release train supports this project's Spring Boot 4.1.x: the stable train
(2025.1.x) is built for Boot 4.0, and the only newer one (2026.0.0-M1) is a milestone that refuses to start on anything but Boot 4.2. The two
ways to use it were downgrading the whole backend or running the platform's single front door on an unsupported combination. Neither is
acceptable, so the gateway is a small purpose-built servlet gateway that reuses the kernel's token verification. Its contract (sections 2 to 6)
does not depend on how it is built; when a Spring Cloud train matches the project's Boot, replacing `GatewayFilter` with Spring Cloud
Gateway's filters is a swap of one class behind the same route table and the same tests. This is a deviation from the written plan and is
recorded here on purpose.

## 2. Routes

Routes are configuration (`app.gateway.routes` in `application.yml`): each owns Ant-style path patterns and names a service; the most
specific matching pattern across all routes wins, whatever order they are written in. `public-paths` are the requests let through without a
token.

| Route | Service | Paths | Open at the gateway |
|---|---|---|---|
| identity | Identity | `/auth/**`, `/identity/**`, `/.well-known/**` | `/auth/**`, `/.well-known/jwks.json` (the step-up endpoints under `/auth` still demand a token, enforced by Identity itself) |
| academic | Academic | `/institution/**`, `/catalog/**`, `/enrollment/**`, `/directory/**`, `/guardian-access/**` | nothing |
| learning | Learning | `/learning/**` | nothing |
| assessment | Assessment | `/assessment/**` | nothing |
| attendance | Attendance | `/attendance/**`, `/staff-attendance/**` | nothing |
| notification | Notification | `/notifications/**` (live delivery `/ws` is not proxied) | nothing |
| ai | AI | `/ai/**` (its facts, `/ai-facts/**`, are served by the service that holds the data and fall to core) | nothing |
| core | everything not yet extracted (record, risk, career, finance, onboarding) | `/**` | API docs and Swagger UI |

`GatewayRouteCoverageTests` reads every endpoint from the running application and fails if any would reach the wrong service, if a route
pattern has no endpoint behind it, or if anything other than sign-in and key publication is open at the gateway. Moving a module into its
own service means editing the route table and that test's module-to-service map together.

## 3. What happens to a request

In order; the first rule that applies decides.

1. **CORS.** The gateway is the only place cross-origin rules live (allowed origins: `app.gateway.cors.allowed-origins`). A preflight from an
   allowed origin is answered with 204 and never forwarded; from any other origin it is 403. `Access-Control-*` headers from a service are
   dropped, so there is one authority.
2. **WebSocket upgrade:** 501 `WEBSOCKET_NOT_SUPPORTED`. Live routing is part of 6A.5; failing loudly beats a connection that half works.
3. **Route.** No route is 404 `NO_ROUTE`.
4. **Authentication,** unless the path is open: the bearer token is verified against Identity's published keys and the revocation list
   (`RemoteTokenAuthenticator`, [identity-service.md](identity-service.md) sections 2 to 4). Missing, malformed, forged, expired or revoked
   is 401 `UNAUTHENTICATED` with `WWW-Authenticate: Bearer`, and the request goes no further. The original `Authorization` header is forwarded
   unchanged: every service verifies for itself, so the gateway is an optimisation and a front door, not a boundary a service may trust.
5. **Rate limit,** per route: per signed-in user, otherwise per client address, one-minute fixed window in Redis (defaults 600 and 120; a
   route may override). Over the limit is 429 `RATE_LIMITED` with `Retry-After`. Redis down means the limiter fails open.
6. **Forward** to the first reachable instance (section 4). Method, path, raw query string and body are preserved; request bodies are
   streamed, not buffered.

Headers: hop-by-hop headers and anything the gateway sets itself are dropped; `X-Forwarded-For/Proto/Host` are set from the real connection
and any the client sent are discarded (they cannot be used to impersonate the edge); `X-Correlation-Id` is the client's if valid, otherwise
generated, and travels to the service and back in the response. A service's `Set-Cookie`, status, headers and body are returned as sent
(redirects are not followed, compressed bodies are not touched). A service's 4xx and 5xx are passed through unchanged.

Failures are the standard error envelope (`code`, `message`, `path`, `requestId`, never an instance address):

| Situation | Status | `code` |
|---|---|---|
| no instance known for the service | 503 (`Retry-After: 5`) | `SERVICE_UNAVAILABLE` |
| nothing answered (refused, unreachable, connection dropped) | 502 | `BAD_GATEWAY` |
| connected but no answer within 30 s | 504 | `GATEWAY_TIMEOUT` |

Retries: only a `GET`, `HEAD` or `OPTIONS` is retried on the next instance, and only when the previous one gave no answer at all. A request
that may have been processed (timeout, or any failure after connecting) and every request that changes data are never sent twice.

## 4. Discovery (health-aware routing)

There is no registry server. An instance is reachable if it is **announced or configured, and not recently failing**:

- **Announced:** a service started with `APP_DISCOVERY_ADVERTISE_URL` (its base URL) writes `setu:services:<service>:<id>` to Redis every 10 s
  with a 30 s TTL (`ServiceRegistrar`), and deletes it on a clean shutdown. A process that crashes or is partitioned stops renewing and
  disappears within the TTL, with no probe needed. Instances are listed by `RedisServiceDirectory`, cached for 2 s.
- **Configured:** `app.gateway.services.<name>.instances` (env `GATEWAY_IDENTITY_URL`, `GATEWAY_ACADEMIC_URL`, `GATEWAY_CORE_URL`) is always
  included, so the gateway works with Redis down or a service that does not announce itself.
- **Passive health:** an instance that refuses or drops a connection goes to the back of the list for 15 s. It is demoted, not banned, so a
  single stray reset on a one-instance service never becomes 15 s of 503; a service that is really down is reported as 502.
- Instances are rotated round-robin.

`ServiceDirectory` is the seam: a Consul or Eureka implementation would replace `RedisServiceDirectory` without touching the filter.

## 5. Observability

Counter `setu.gateway.requests{route,outcome}`, outcomes `proxied`, `unauthenticated`, `rate_limited`, `unavailable`, `bad_gateway`,
`gateway_timeout`, `no_route`, `preflight`, `preflight_refused`, `websocket_unsupported`, `aborted`. Every log line carries the correlation id.
The gateway's own `/actuator/health` and `/actuator/info` are served locally and never forwarded.

## 6. What it does not do (yet)

- **WebSocket/STOMP routing.** The live attendance board is served by the Attendance service itself; clients connect to it directly, and the gateway refuses an upgrade with 501 instead of half-proxying it. TLS termination is assumed at the deployment edge.
- **Request size limits** and **per-route timeouts** (one global timeout today).
- **Tracing across the hop** beyond the correlation id (6C.1).
- **Running as a real server on this machine.** Embedded Tomcat does not start on this Windows setup (see `backend/README.md`), so the gateway
  is verified as a whole application through MockMvc, with the real HTTP client talking to a real blocking socket stub and the real Redis.

## 7. Tests that hold this contract

| Contract | Test |
|---|---|
| Most-specific route wins, public paths, lookalike prefixes | `RouteTableTests` |
| Every endpoint reaches its owning service; no dead route; only sign-in and keys are open | `GatewayRouteCoverageTests` |
| 401 for bad/revoked tokens before forwarding, header handling, spoof protection, correlation id, pass-through, 429, 503, 502, 504, retry rules, CORS, WebSocket | `GatewayFilterTests` |
| The real HTTP client: bodies, chunking, redirects, compressed bodies, refused/timed-out/dropped connections | `ApacheUpstreamTests` |
| Announce, expire, demote, rotate, Redis failure; rate limiter counts per caller and fails open | `RedisServiceDirectoryTests` |
| The whole application with no database or other service code, forwarding to a socket stub with verified tokens and revocation | `GatewayWiringTests` |
