# infra/db — Flyway migrations

`migrations/` holds every schema change, one file per migration, named
`V{n}__{description}.sql` in strictly increasing order (currently V1 to V76). **Never edit a migration
that has already run in any shared environment** — write a new one instead, per
[TRD.md §10](../../docs/TRD.md#10-infra). The order and the reasoning behind it are in
[docs/migration-order.md](../../docs/migration-order.md).

## Why migrations live here and not in `backend/src/main/resources/db/migration`

Keeping migrations outside the backend module keeps the schema portable and
reviewable independent of the Spring Boot app, and matches this repo's layout
(`docs/TRD.md` §1, §10). The backend points Flyway at this folder in
`backend/src/main/resources/application.yml`:

```yaml
spring:
  flyway:
    locations: filesystem:../infra/db/migrations
```

(Path is relative to the backend module's working directory.)

## Schema per module

Each backend module owns its own PostgreSQL schema (`identity`, `institution`, `directory`, `guardian_access`, `catalog`, `enrollment`, `attendance`, `learning`,
`assessment`, `record`, `finance`, `career`, `risk`, `notification`, `analytics`, `governance`), and a module never reads another module's tables. Ownership is listed in
[docs/schema-per-module-map.md](../../docs/schema-per-module-map.md) and
[docs/table-ownership-map.md](../../docs/table-ownership-map.md); the entity-relationship diagrams are under
[docs/erd/](../../docs/erd/).

## Conventions that have already bitten us

- **Optimistic locking:** every mutable aggregate has a `version` column.
- **Time of day is stored as integer minutes since midnight, not `TIME`.** The backend runs Hibernate with `jdbc.time_zone: UTC`,
  which shifts `LocalTime` values on a JVM that is not in UTC (a stored `00:00` came back as `18:30`). Instants use `timestamptz`.
- **Uniqueness races are handled by constraints**, with the insert wrapped in a small `REQUIRES_NEW` guard so the conflict is
  reported cleanly. Add the unique index in the migration, do not rely on a check-then-insert.
- **References, not bytes:** columns such as `lessons.content_ref` and `lesson_resources.media_ref` hold a link (`https://`,
  `ref://` or `s3://`); binary content never lives in PostgreSQL. The allowed forms are enforced in the application
  (`ContentReferences`).
- **Adding a table changes a test.** `MigrationIntegrationTests` asserts the number of domain tables, so a new table needs the
  expected count (and a comment saying why) updated in the same change. Adding a column does not.

## Running migrations locally

With the Docker Compose stack up (`infra/docker/`), Spring Boot runs these
migrations automatically on startup via its Flyway integration. To run them by hand
instead:

```bash
flyway -url=jdbc:postgresql://localhost:5433/setu -user=setu -password=<POSTGRES_PASSWORD from infra/docker/.env> \
  -locations=filesystem:./migrations migrate
```

The host port is the `POSTGRES_PORT` in `infra/docker/.env` (5433 with this repo's `.env.example`).

## Starting from a clean database

Some tests (the demo-data verification tests) need an empty database, and the live demo is easiest to reason about from a fresh
seed. Wipe the volumes and let the migrations and seeder run again:

```bash
cd infra/docker && docker compose down -v && docker compose up -d
```
