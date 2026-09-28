# infra/docker — local dev stack

Brings up PostgreSQL (with pgvector), Redis, and Kafka for local development, per
[TRD.md §1, §10](../../docs/TRD.md#1-stack-locked--do-not-substitute-without-updating-this-document).
The backend and frontend are not containerized yet — run them directly
(`AGENTS.md` prompt 0.2 onward) against this stack.

## Start

```bash
cd infra/docker
cp .env.example .env   # adjust ports/passwords if needed, first time only
docker compose up -d
docker compose ps      # confirm all three services are healthy
```

## Services

| Service | Port (host) | Notes |
|---|---|---|
| postgres | 5432 | `pgvector/pgvector:pg16` — pgvector extension available, enabled by [migration V1](../db/migrations/V1__init.sql) |
| redis | 6379 | Cache only — never a source of truth (TRD §1) |
| kafka | 9092 | Single-node KRaft mode (no Zookeeper) — outbox event target (TRD §4) |

## Stop

```bash
docker compose down          # stop, keep data
docker compose down -v       # stop and wipe volumes (fresh start)
```

## Connecting

- Postgres: `jdbc:postgresql://localhost:5432/setu`, user/password from `.env`
  (defaults: `setu` / `setu_dev_password` — dev only, never reused anywhere real)
- Redis: `localhost:6379`, no auth in dev
- Kafka bootstrap: `localhost:9092`
