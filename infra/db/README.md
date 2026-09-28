# infra/db — Flyway migrations

`migrations/` holds every schema change, one file per migration, named
`V{n}__{description}.sql` in strictly increasing order. **Never edit a migration
that has already run in any shared environment** — write a new one instead, per
[TRD.md §10](../../docs/TRD.md#10-infra).

## Why migrations live here and not in `backend/src/main/resources/db/migration`

Keeping migrations outside the backend module keeps the schema portable and
reviewable independent of the Spring Boot app, and matches this repo's layout
(`docs/TRD.md` §1, §10). When the backend is scaffolded (`AGENTS.md` prompt 0.2), point
Spring's Flyway integration at this folder instead of the classpath default:

```yaml
spring:
  flyway:
    locations: filesystem:../infra/db/migrations
```

(Path is relative to the backend module's working directory — adjust if the module
layout changes.)

## Running migrations locally

With the Docker Compose stack up (`infra/docker/`), Spring Boot runs these
migrations automatically on startup via its Flyway integration. To run them by hand
instead:

```bash
flyway -url=jdbc:postgresql://localhost:5432/setu -user=setu -password=setu_dev_password \
  -locations=filesystem:./migrations migrate
```
