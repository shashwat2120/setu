# AI service (Sprint 6B.3)

The `aiexplain` module (Spring AI chat orchestration, the advisor's tools, conversation memory, rate limiting, background jobs) runs as its
own service: `com.setu.aiexplain.AiServiceApplication`, `setu.service=ai`, port 8088, gateway route `/ai/**`. The rule it was extracted
under is the one the project started with: **the AI module never gets a datasource** (AGENTS.md rule 4, canonical spec section 10). In one
process that was a convention enforced by an architecture test; as a service it is a fact of the build: the persistence auto-configurations are
excluded, so there is no driver, no JPA and no Flyway to misuse.

## 1. Two modules where there was one

| Module | Holds | Knows about |
|---|---|---|
| `aiexplain` (the **AI service**) | what the model sees (the nine tools: name, description, parameters), the chat and streaming endpoints, jobs, memory, rate limit, provider fallback | itself and a handful of kernel contracts. No other module, no datasource |
| `aifacts` (in the **data service**, today the core) | what the tools return and who may see it: `AiFactsService` (the former `AiToolService` logic), `AiAccessSupport`, the `/ai-facts/**` API | the modules the facts come from, as `aiexplain` used to |

They meet at one interface, `com.setu.shared.aifacts.AiFacts` (the **secure tool boundary**): `caller()`, nine facts and `audit(...)`. In one
process `aifacts.InProcessAiFacts` answers it directly; in the AI service `RemoteAiFacts` answers it over HTTP from `/ai-facts/**`.
`setu.ai-facts.url` selects: set, the remote client; unset, in-process (the monolith, unchanged behaviour).

## 2. The boundary holds across the network hop

1. **The user's own token travels.** `RemoteAiFacts` presents the bearer token of the person being served (kept as the authentication's
   credentials by `RemoteTokenFilter`, and carried to tool threads and job workers with the security context). The data service authenticates it
   like any request of that user and decides with that user's identity, tenant, roles and relationships. The AI service holds **no credential of
   its own** that could read anything (there is no service token for facts), so a compromised or misled advisor can see no more than the user.
2. **The model never decides who may see what.** Tool parameters name which student a question is about, or are absent (the caller). The check
   (own / advisor of that student / administrator / guardian with an active grant for that permission) runs in `AiAccessSupport`, in the data
   service, every time. None of the nine tools takes a tenant, a user, a token or a role (`AiToolServiceTests`).
3. **A refusal is an answer.** "Student not found" and "not authorized ..." come back as a normal `{"reason": ...}` result for the model to
   report; unchanged from the in-process tool (`AiToolAuthorizationTests`, `AiFactsControllerTests`).
4. **Unavailable is never empty.** A timeout, refused connection, error status, malformed answer, open circuit or full bulkhead is
   `AiFacts.Unavailable`; the tool turns it into `{"reason": "this information is not available right now..."}` so the model can only tell the
   user the truth. No fallback value exists anywhere on this path (`RemoteAiFactsTests`).
5. **Bounded.** 500 ms connect, 8 s response, no retries; circuit breaker (50% of 10+ calls) and a 20-caller bulkhead per `GuardedCall`
   (data-ownership.md section 3). A 4xx, for example an expired token, is the caller's problem and does not count against the data service.
6. **RAG keeps its boundary.** `searchCollegePolicy` runs in the data service, which embeds the query and filters by the caller's own college
   **before** anything is retrieved; retrieved text is returned as data, never as instructions (`AiPolicySearchTests`, prompt-injection tests
   unchanged). The AI service never sees another college's documents and has no embedder.

## 3. Audit

`audit(action, correlationId, details)` is the only write the advisor can make. Over the network it is `POST /ai-facts/audit`; the data service
records it for **the token's** user and college (never from the body), only for the advisor's own actions (`AI_CHAT_COMPLETED`,
`AI_CHAT_RATE_LIMITED`, `AI_CHAT_FALLBACK_USED`, `AI_CHAT_TIMEOUT`, `AI_CHAT_PROVIDER_ERROR`, `AI_JOB_SUBMITTED/COMPLETED/FAILED`), with at most
20 details of at most 500 characters. Anything else is a 400 and writes nothing.

* **The record of an answer is part of answering.** If `AI_CHAT_COMPLETED` cannot be written, the answer is **not given** (503), and no other
  model is tried. Canonical spec section 10 wants every explanation's input recorded; a service that could answer unrecorded would break that
  silently.
* Diagnostic records (rate limited, timeout, provider error, fallback used) are best effort: a failure to write them is logged, never turned
  into a failed call.

## 4. Background jobs (queued / running / completed)

* `POST /ai/jobs {"message": ...}` -> `202 {jobId, status: QUEUED, statusUrl, topic}`. The same validation as `/ai/chat` (400), and 503 when
  nothing could be accepted: the queue is full, or the caller could not be identified. Never accepted-then-dropped.
* The job runs the same single bounded model call as `/ai/chat`, **as the person who asked**: identity and token are captured at submission, so
  its facts are authorized for them exactly as for a synchronous question. A fixed number of workers (4) and a bounded queue (50).
* Status moves `QUEUED -> RUNNING -> COMPLETED | FAILED` (`FAILED` carries `invalid_request`, `rate_limited`, `unavailable`, `queue_full` or
  `internal_error`). Each change is pushed to **`/topic/ai/jobs/<userId>`**, status only, never the answer text; the result is read with
  `GET /ai/jobs/{id}`.
* A job is visible to its owner only: anyone else asking, and a job that never existed or expired, get the same 404.
* Records live in Redis for an hour (`setu:ai:job:<id>`). Nothing here needs a database, and an answer is not kept longer than it is useful.
  A restart loses queued jobs; they read as not found and the client resubmits.
* Subscription is identity, not permission (`AiJobSubscriptionAuthorizer`, `OwnTopic`): a user listens to their own topic and no other; the
  destination is parsed, and wildcards, extra segments, shortened ids and other users' ids are refused.

## 5. What stays where

* Conversation memory is in the AI service's process, keyed by (user, college), last six exchanges, 30 minutes idle: unchanged, and it never
  holds tool arguments or results. Rate limiting is Redis-backed per (college, user): unchanged.
* The AI service reaches the data service through the gateway (`GATEWAY_URL`), so discovery and health-aware routing apply; `/ai-facts/**` falls
  to the core route. The gateway relays WebSocket upgrades only on its live route (`/ws`, the live attendance board), and answers an upgrade anywhere else with 501,
  so clients subscribe to job status on the AI service directly; it would need a path and a route of its own to go through the gateway.
* `/ai-facts/**` is an ordinary authenticated API: a user who calls it directly gets what the advisor would have got for them, which they could
  already read through the normal endpoints.

## 6. Providers: Gemini first, Ollama only as the fallback

* **Gemini is the primary provider everywhere**: the running services and every backend test (`AI_PROVIDER` defaults to `google-genai`). Ollama
  (the local `llama3.2:3b`) is the fallback only, because it is slow on this machine, minutes where Gemini takes seconds.
* **No key, no failure.** A run without `GEMINI_API_KEY` starts with Ollama as primary and says so in the log (`AiProviderSafetyPostProcessor`);
  a present-but-wrong key starts normally and falls back at request time. `AI_PROVIDER=ollama` forces the local model.
* **Retries are capped** (`spring.ai.retry`: 2 attempts, quick backoff, none for client errors such as a bad key or an exhausted quota; the hand-built
  Ollama fallback uses the short retry template). Spring AI's default is ten attempts backing off to minutes, which would hide a Gemini
  outage from `AiChatService` and delay the fallback it is built to make. The advisor's own budgets decide, not silent retries.
* **Embeddings for policy search follow the chat provider**: Gemini's `gemini-embedding-001` (768 dimensions) when Gemini is primary and has a key,
  otherwise the built-in local embedder (not Ollama); Ollama's embedder only when asked for (`app.ai.embedding.provider=ollama`). Chunking is plain
  code, no model. Stored vectors carry their embedder's model id and a query only uses vectors of the same model, so passages indexed under another
  embedder are not matched by the vector half of the hybrid search until they are re-indexed (the full-text half still finds them).
* **Tests that call the real local model are tagged `live-ollama`** and skipped by a plain `mvn test` (`AiChatFallbackTests` real-model cases,
  `AiChatGeminiMissingApiKeyTests`); the fallback logic itself is covered by scripted models. Run them when touching the fallback path:
  `./mvnw test -DexcludedGroups=none -Dtest=AiChatFallbackTests,AiChatGeminiMissingApiKeyTests`. `AiChatGeminiTests` runs with the rest (skipped
  when no key is configured). If the key's daily quota is exhausted the application correctly falls back to Ollama and any test asserting
  `gemini` fails for a reason that has nothing to do with the code: wait for the quota to reset.

## 7. Tests

`AiServiceWiringTests` (no datasource or other module in the context; every call to the data service carries the asker's own token; the model's
answer is withheld when the data service is down or the record cannot be written; a job runs to completion and is announced in order on the
owner's topic only; another user cannot see it), `RemoteAiFactsTests` (paths, token, refusal, every failure kind, breaker behaviour, audit body),
`AiToolServiceTests`, `AiFactsControllerTests` (401 everywhere, caller identity, refusal as an answer, audit cannot write other actions or
other users), `AiJobSubscriptionAuthorizerTests`, `AiExplainBoundaryTests` (the module depends on no other module, no persistence, no
repository, no audit writer), and the existing authorization, fallback, sources and policy tests, which now run through the same seam.

## 8. Not done

* The data service is still the core (the facts need Risk, Finance, Career, Record, Attendance, Learning, Assessment and Academic in one
  place). Splitting `aifacts` further, one fact per owning service, is possible behind the same `AiFacts` interface and is the natural next
  step once Record, Risk, Finance and Career are extracted.
* Streaming (`/ai/chat/stream`) goes through the same boundary but was not exercised over a real socket here (embedded servers do not start on
  the Windows development machine); the model call, tool wrapping and context carrying are the code the tests do cover.
* A job that outlives its token (more than the access token lifetime) would see its facts refused. Jobs are one bounded model call (60 s), far
  inside that, so no refresh is attempted.
