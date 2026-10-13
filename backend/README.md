# backend: Spring Boot modular monolith

Java 21, Spring Boot 4.1.1, Spring Modulith, Spring Data JPA, Spring Security 7, Spring AI 2.x, Flyway, Kafka (transactional outbox),
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
| `JWT_SECRET` | dev-only value | **Must** be overridden in any real deployment |
| `FRONTEND_BASE_URL` | `http://localhost:5180` | Origin used to build the link to the reset page in emails |
| `DEMO_DATA_ENABLED` | `false` | Seed the demo colleges, people and content |
| `AI_PROVIDER` | `ollama` | `google-genai` for Gemini as the primary provider |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | none, `gemini-3.8-flash` | Gemini credentials and model |
| `OLLAMA_BASE_URL`, `OLLAMA_MODEL` | `http://localhost:11434`, `llama3.2:3b` | Local model, always the fallback |
| `AI_PRIMARY_TIMEOUT_SECONDS`, `AI_REQUEST_TIMEOUT_SECONDS` | `25`, `60` | Budget for the Gemini attempt, and for the whole request |
| `AI_RATE_LIMIT_MAX_REQUESTS` | `20` | Advisor requests per window per user |
| `BREVO_API_KEY`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME` | none | Real password-reset email through Brevo; with no key nothing is sent |
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
address you control and set `BREVO_API_KEY` and a `MAIL_FROM_ADDRESS` that is a verified sender in Brevo.

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
