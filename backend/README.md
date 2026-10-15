# backend: Spring Boot modular monolith

Java 25 LTS, Spring Boot 4.1.1, Spring Modulith, Spring Data JPA, Spring Security 7, Spring AI 2.x, Flyway, Kafka (transactional outbox),
Redis. One deployable application split into strictly bounded modules; the design is in [ARCHITECTURE.md](ARCHITECTURE.md) and the
locked contract is [docs/FINAL_CANONICAL_PROJECT_SPEC.md](../docs/FINAL_CANONICAL_PROJECT_SPEC.md).

## Modules

`src/main/java/com/setu/<module>`: `identity`, `institution`, `directory`, `guardianaccess`, `catalog`, `enrollment`, `attendance`,
`learning`, `assessment`, `record`, `finance`, `career`, `risk`, `aiexplain`, `notification`, `analytics`, plus `shared` (audit log,
outbox, tenant context, demo-data seeder).

Module boundaries are enforced, not just agreed: `ModularityTests` fails the build if one module reaches into another's repositories
or entities. A service another module may call is marked `@NamedInterface`; everything else is private to its module. The `aiexplain`
module has no datasource of its own and reaches data only through application-owned, authorization-checked tools.

## Run it

The database, Redis and Kafka come from [infra/docker](../infra/docker/README.md); start that first.

On a normal machine the backend runs like any Spring Boot app:

```bash
DB_PORT=5433 REDIS_PORT=6380 KAFKA_BOOTSTRAP_SERVERS=localhost:9093 DEMO_DATA_ENABLED=true ./mvnw spring-boot:run
```

(`DEMO_DATA_ENABLED=true` seeds the demo college; it is off by default and never on in the tests.)

### Running on Windows

On the Windows development machine this project was built on, embedded Tomcat cannot start: `java.nio.channels.Selector.open()` fails at
the JDK/OS level. So the live backend is run through a **dev bridge** that boots the same Spring context and forwards real HTTP
requests into it:

```bash
DB_PORT=5433 REDIS_PORT=6380 KAFKA_BOOTSTRAP_SERVERS=localhost:9093 ./mvnw -q test "-Dtest=LiveDevBridge#serve" -DfailIfNoTests=false
```

Wait for `LIVE_DEV_BRIDGE_LISTENING_ON_8089`; the Vite dev server proxies to that port. The bridge seeds demo data on startup. It is
a plain HTTP/1.1 relay, so WebSocket/STOMP features (live QR attendance) cannot be watched live through it, and the frontend says so
rather than faking an update. Stop it by killing the Java process.

The same JDK defect delays the Kafka producer's first start. Events wait in the outbox table (`governance.outbox_events`, status `NEW`)
and publish when the producer comes up; this can take minutes and is not predictable.

## Configuration

Settings come from environment variables with defaults in `src/main/resources/application.yml`. For local secrets, create the
git-ignored `backend/.env.local` (it is loaded automatically); never commit keys.

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `setu`, ... | PostgreSQL. Use `DB_PORT=5433` with this repo's Docker defaults |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Redis (rate limiting and QR state; never a source of truth) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Outbox publishing |
| `SERVER_PORT` | `8080` | HTTP port (the dev bridge uses 8089) |
| `JWT_SECRET` | dev-only value | Keys the password-reset-code HMAC only; **must** be overridden in any real deployment |
| `JWT_SIGNING_KEY` | empty (ephemeral key, warned) | Base64 PKCS#8 EC P-256 private key that signs access tokens (ES256); make one with `node tools/generate-jwt-key.js`. Other services verify with the public keys at `/.well-known/jwks.json` |
| `JWT_PREVIOUS_PUBLIC_KEYS` | empty | Comma-separated Base64 X.509 public keys of retired signing keys, still published during a rotation |
| `FRONTEND_BASE_URL` | `http://localhost:5180` | Origin used to build the link to the reset page in emails |
| `DEMO_DATA_ENABLED` | `false` | Seed the demo colleges, people and content |
| `AI_PROVIDER` | `ollama` | `google-genai` for Gemini as the primary provider |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | none, `gemini-3.8-flash` | Gemini credentials and model |
| `EMBEDDING_PROVIDER` | `auto` | Embeddings for college-policy search: `auto` (Gemini when Gemini is the chat provider, otherwise the built-in local embedder), `gemini`, `ollama` (`EMBEDDING_OLLAMA_MODEL`, a 768-dimension model such as `nomic-embed-text`) or `local` |
| `OLLAMA_BASE_URL`, `OLLAMA_MODEL` | `http://localhost:11434`, `llama3.2:3b` | Local model, always the fallback |
| `AI_PRIMARY_TIMEOUT_SECONDS`, `AI_REQUEST_TIMEOUT_SECONDS` | `25`, `60` | Budget for the Gemini attempt, and for the whole request |
| `AI_RATE_LIMIT_MAX_REQUESTS` | `20` | Advisor requests per window per user |
| `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM_ADDRESS` | none, none, `smtp.gmail.com`, `587`, the user | Reset email over SMTP; with no user or password nothing is sent |
| `METRICS_SCRAPE_TOKEN` | none | Token Prometheus uses for `/actuator/prometheus`; with none set the endpoint is closed to everyone. See [infra/docker/README.md](../infra/docker/README.md#observability) |
| `EMAIL_PREVIEW_ENABLED`, `EMAIL_PREVIEW_DIR` | `false`, `target/demo-email-outbox` | Also write each reset email to a local file |

### AI advisor

The advisor answers through a fixed set of tools (attendance, academic progress, deadlines, fees, skill gaps, college policy search,
learning resources), each scoped to the signed-in person. It uses Gemini when `AI_PROVIDER=google-genai` and falls back to the local
Ollama model if Gemini fails or is too slow. The Gemini attempt has its own, shorter time budget (`AI_PRIMARY_TIMEOUT_SECONDS`) so a
hung provider does not make every answer wait out the full request timeout before the fallback starts.

**Choose a model that answers for your key.** Models differ in availability and speed by account. On the key used while building this
project, `gemini-3.8-flash` accepted requests but never returned an answer, which made every advisor call take about 98 seconds (60s
waiting, then the slow local fallback). `gemini-3.5-flash-lite` answered in roughly 1.5 to 3 seconds. Before settling on a model, time one
call directly:

```bash
curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" --max-time 30 \
  "https://generativelanguage.googleapis.com/v1beta/models/<MODEL>:generateContent" \
  -H "x-goog-api-key: $GEMINI_API_KEY" -H "Content-Type: application/json" \
  -d '{"contents":[{"parts":[{"text":"Say hi."}]}]}'
```

Free-tier keys are also rate limited and can return 503 under load; the fallback exists for that.

### College policy search (RAG)

The advisor's `searchCollegePolicy` tool searches the college's own policy documents. Each document is split into passages
(`institution.policy_chunks`), each with a pgvector embedding and a full-text vector; a question is matched by both and the two
rankings are merged (reciprocal-rank fusion). The tenant and the document-access check are applied **inside the SQL, before anything is
ranked**: the caller's college, and the access codes the caller actually holds, so a restricted passage is never ranked, returned or shown
to the model, and cannot crowd out an allowed one. A question that matches nothing returns nothing.

- **Gemini first**, like chat: with `AI_PROVIDER=google-genai` and a key, passages are embedded with `gemini-embedding-001`
  (768 dimensions) and a question with none of the policy's words ("how many classes can I miss") still finds the attendance policy.
- **Fallbacks are honest.** If Gemini is unavailable the vector half is skipped and full-text search answers; chunks are stored without a
  vector and get one on a later pass (every minute). There is no automatic switch to a second embedding model, because vectors from
  different models cannot be compared. `ollama` is an explicit, opt-in provider.
- **No key, or `local`:** a built-in embedder (hashed words and word pairs, deterministic, no download) keeps the same pgvector storage
  and ranking working offline. It matches shared wording, not meaning, and the tests use it.
- Changing the embedding model re-embeds existing passages in the background.

### Password reset and email

`POST /auth/password/reset/initiate` always answers the same way whether or not the account exists, and emails a 6-digit code. The
code is valid for 15 minutes, works once, and allows 5 wrong guesses; asking for a new code cancels the older ones. Only a keyed
hash of the code is stored (`identity.password_reset_codes`), and each guess and the final use are single guarded UPDATEs, so
parallel requests cannot exceed the guess budget or use a code twice. `POST /auth/password/reset/confirm` takes `{email, code,
newPassword}` and answers 204, or a 400 that is identical for an unknown email, a wrong code, an expired code and a used code.
A password of 8 to 128 characters that is not on a small common-password list and is not the account's own email is required;
a rejected password is reported separately (`password_too_weak`) and costs no guess. A successful reset signs out every existing
session and cancels the user's other codes. Requesting a code is rate limited (3 per 15 minutes) and entering codes too
(10 per email and 60 per IP per 15 minutes, answered with 429).

Seeded accounts use `@demo.setu.local` addresses, which cannot receive mail. To receive a real reset email, use an account whose
address you control and configure the SMTP sender:

- **Gmail (no domain or sender verification).** Turn on 2-step verification for the Gmail account, create an app password at
  <https://myaccount.google.com/apppasswords>, then put these in the git-ignored `backend/.env.local`:
  `SMTP_USER=you@gmail.com`, `SMTP_PASSWORD=<the 16-character app password>`. Mail goes out from that Gmail
  address over STARTTLS (port 587); Gmail only allows its own address, or a verified alias set in `SMTP_FROM_ADDRESS`, as the sender.
  Any other SMTP server works with `SMTP_HOST` and `SMTP_PORT` (port 465 uses SSL), but Microsoft 365 often has SMTP login disabled
  by the organisation's admin.

A send that fails (wrong password, server unreachable, 10 second timeout) never changes what the visitor sees; it is logged with the
address masked and never with the password.

### Services that can run on their own (Sprint 6)

The monolith is still the default. Four of its parts also start alone, as the first step of the service split; each is inert inside the
monolith and has its contract in `docs/contracts/`.

| Service | Entry point | Port | Needs |
|---|---|---|---|
| Identity | `com.setu.identity.IdentityServiceApplication` | 8081 | Postgres, Redis, Kafka; `JWT_SIGNING_KEY` (see `tools/generate-jwt-key.js`); publishes its keys at `/.well-known/jwks.json` |
| Gateway | `com.setu.gateway.GatewayApplication` | 8080 | Redis only (no database); routes `/auth` to Identity, the Academic paths to Academic, the rest to the remaining monolith; services reach it via `APP_DISCOVERY_ADVERTISE_URL` (their own base URL) or `GATEWAY_*_URL` |
| Config server | `com.setu.config.ConfigServerApplication` | 8888 | a token per application (`APP_CONFIGSERVER_TOKENS_<APP>`); serves `infra/config` plus a git-ignored secrets directory; services opt in with `SPRING_CONFIG_IMPORT=optional:setu-config:http://localhost:8888` and `SETU_CONFIG_TOKEN` (`infra/config/README.md`) |
| Academic | `com.setu.academic.AcademicServiceApplication` | 8082 | the same, plus `app.security.jwt.jwks-uri` pointing at Identity's key set; builds its read model of roles and people from Identity's events |

Run one with `./mvnw spring-boot:run -Dspring-boot.run.main-class=com.setu.academic.AcademicServiceApplication`. Neither runs Flyway:
migrate with the monolith first. On this Windows machine a real embedded server does not start (see "Running on Windows"), so both are
verified through `IdentityServiceWiringTests` and `AcademicServiceWiringTests`.

## Tests

```bash
./mvnw test                                 # everything
./mvnw -q test -Dtest=ModularityTests       # module boundaries
```

Most tests are integration tests against the real PostgreSQL from the Docker stack. Use a fresh database (`docker compose down -v &&
docker compose up -d` in `infra/docker`) before a full run; the demo-data verification tests require it. The suite also expects a local
Ollama with the configured model for the tests that exercise the real fallback.

## API reference

With the app running, the OpenAPI document is at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`. A hand-maintained catalog of the
contract is in [docs/openapi-contract-catalog.md](../docs/openapi-contract-catalog.md), the permission model in
[docs/authorization-matrix.md](../docs/authorization-matrix.md), and the Kafka events in
[docs/kafka-event-catalog.md](../docs/kafka-event-catalog.md).
