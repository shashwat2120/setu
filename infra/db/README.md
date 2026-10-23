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

## A service's own migrations

`migrations/` is the monolith's: the core's Flyway applies it to the shared database. A service that has been given a **database of its own** (today Notification, Attendance, Assessment and Learning,
`docs/contracts/data-ownership.md` section 4a) migrates that database itself, from two folders:

| Folder | What it holds | Applied by |
|---|---|---|
| `platform/` | changes to the tables every database carries a copy of: `governance` (outbox, audit log, idempotency keys) and `public.event_publication` | the core, **and every service with a database of its own** |
| `services/<service>/` | changes to the schemas that service owns | that service to its own database, **and the core to the shared database's copy** (so the copy the core still validates its entities against stays in step) |

Rules, held by `ServiceMigrationsTests`: a version number appears **once** across `migrations/`, `platform/` and every `services/` folder (so the shared database's history and a
service's can never disagree about what a version means); versions in the new folders are above 103, the version everything was at when the first service was split off;
a file in `services/<service>/` touches only that service's schemas and a file in `platform/` only the platform tables; and the image, the core's configuration and the
service's compose entry each list every folder they need.

A service's database starts with a Flyway history of one baseline row (written by `split/provision-service-db.sh`), so its Flyway applies only what comes after the copy it was
given. The monolith's own history is never copied. Adding a migration for a split service: put `V<next>__what.sql` in `services/<service>/`; nothing else changes.

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
