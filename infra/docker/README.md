# infra/docker — local dev stack

Brings up PostgreSQL (with pgvector), Redis, and Kafka for local development, per
[TRD.md §1, §10](../../docs/TRD.md#1-stack-locked--do-not-substitute-without-updating-this-document).
The backend and frontend are not containerized yet: run them directly against this stack
(see the root [README](../../README.md#quick-start-local)).

## Start

```bash
cd infra/docker
cp .env.example .env   # first time only; adjust ports/passwords if needed
docker compose up -d
docker compose ps      # confirm all three services are healthy
```

## Services and ports

The host ports come from `.env`. The compose file's own fallbacks are the standard ports (5432/6379/9092), but this repo's
`.env.example` deliberately maps **5433 / 6380 / 9093** so the stack does not clash with other projects' containers on the same machine.

| Service | Host port (`.env.example`) | Container port | Notes |
|---|---|---|---|
| postgres | 5433 (`POSTGRES_PORT`) | 5432 | `pgvector/pgvector:pg16`: pgvector is available, enabled by [migration V1](../db/migrations/V1__init.sql) |
| redis | 6380 (`REDIS_PORT`) | 6379 | Cache only, never a source of truth (TRD §1) |
| kafka | 9093 (`KAFKA_PORT`) | 9092 | Single-node KRaft mode (no Zookeeper); the outbox event target (TRD §4) |
| prometheus | 9091 (`PROMETHEUS_PORT`) | 9090 | Scrapes the backend every 15 s (see Observability below) |
| grafana | 3001 (`GRAFANA_PORT`) | 3000 | Dashboards; sign in as `admin` / `admin` (local default, change with `GRAFANA_ADMIN_PASSWORD`) |

## Observability

The backend publishes Micrometer metrics at `/actuator/prometheus`: JVM, HTTP requests and latency, the database pool, and the Setu
business counters (`setu_auth_logins_total` by outcome, `setu_ai_chat_requests_total`, `setu_outbox_events_published_total` by
event type, and the `setu_outbox_pending` backlog). The endpoint is closed to everyone except a request that carries one dedicated
scrape token, so it is neither public nor reachable with an ordinary user token.

One-time setup, then start the stack:

```powershell
pwsh infra/docker/observability/init-scrape-token.ps1   # creates the token and shares it with the backend
docker compose up -d                                    # from infra/docker
```

Restart the backend after the first run so it reads `METRICS_SCRAPE_TOKEN` from `backend/.env.local`. Then open Grafana at
`http://localhost:3001` → Dashboards → Setu → **Setu: backend overview** (provisioned automatically from
`observability/grafana/dashboards`). Prometheus is at `http://localhost:9091` (Status → Targets should show `setu-backend` UP).
Prometheus reaches the backend through `host.docker.internal:8089` (the dev bridge); a normally run backend uses 8080, so edit
`observability/prometheus.yml`. The token file `observability/scrape-token.local` is git-ignored.

If you change a port here, give the backend the matching value:

| `.env` | Backend environment variable |
|---|---|
| `POSTGRES_PORT` | `DB_PORT` |
| `REDIS_PORT` | `REDIS_PORT` |
| `KAFKA_PORT` | `KAFKA_BOOTSTRAP_SERVERS=localhost:<port>` |

## Stop and reset

```bash
docker compose down          # stop, keep data
docker compose down -v       # stop and wipe volumes (fresh database, fresh seed on next backend start)
```

Use `down -v` before running the full backend test suite or the demo-data verification tests: they expect an empty database.

## Connecting

- Postgres: `jdbc:postgresql://localhost:5433/setu`, user and password from `.env`
  (defaults: `setu` / `setu_dev_password`, dev only, never reused anywhere real)
- Redis: `localhost:6380`, no auth in dev
- Kafka bootstrap: `localhost:9093`

## On Windows

Docker Desktop must be running before `docker compose` works. If you see "failed to connect to the docker API ... dockerDesktopLinuxEngine",
the engine is not up yet (after a reboot, for example): start Docker Desktop and wait until `docker info` answers.

The Kafka outbox publisher can take a while to start publishing on some Windows machines (a JDK networking quirk described in
[backend/README.md](../../backend/README.md#running-on-windows)). Events wait safely in the outbox table with status `NEW` and
publish once the producer comes up.
