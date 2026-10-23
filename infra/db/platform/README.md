# Platform migrations

Migrations for the tables **every process carries a copy of**: the outbox, the audit log and the idempotency keys (`governance`), and Spring Modulith's event log
(`public.event_publication`). While there was one database there was one copy of each and they lived with the other migrations. A service with a database of its
own has its own copy, so a change to one of these tables has to reach every database: the core applies this folder to the shared database and every service with
a database of its own applies it to its own. (`infra/db/README.md`, "A service's own migrations".)

* Files are named `V<n>__<description>.sql`, with `n` greater than 103 and **unique across `migrations/`, `platform/` and every folder under `services/`**: Flyway refuses a
  start-up that finds the same version twice, and `ServiceMigrationsTests` checks it without a database.
* They may touch only `governance` and `public.event_publication`. A change to anything a service owns belongs in `services/<service>/`.
* There are none yet: the folder exists so that the first one has a place, and so that every database's list of locations already names it.
