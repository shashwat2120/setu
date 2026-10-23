#!/usr/bin/env bash
# Gives one service a database of its own on a server of its own (Sprint 6, docs/contracts/data-ownership.md section 4a).
#
#   provision-service-db.sh <service> <schema[,schema...]>
#
# Unlike split-service.sh (a development rehearsal that drives the compose Postgres through `docker exec`), this runs anywhere psql and pg_dump can reach both servers, so a
# compose one-shot can run it. It copies, from the shared database into the service's own:
#   - the schemas the service owns, structure and data (its read models and processed-event ids come with it, so it carries on where the monolith left off);
#   - the platform's cross-cutting tables, structure only: the outbox, audit log and idempotency keys (governance), and Spring Modulith's event log (public.event_publication).
#     These are shared tables in the monolith; a service with a database of its own has its own, empty, which is the point;
#   - the extensions the schemas use.
# Then it gives the service its database login (infra/db/roles/service-roles.sql). It does NOT copy Flyway's history: the service never migrates (see the contract's limits).
#
# Safe to run again. The load is ONE transaction and the last thing in it is public.event_publication, so that table existing means the load finished; a failed run leaves
# nothing that could be mistaken for a finished one. A run that finds the database loaded only re-applies the login's grants. It ends by comparing the row count of every
# copied table with the source and exits non-zero on any difference.
#
# Environment: PGPASSWORD; SRC_HOST (default postgres), SRC_DB (default setu_demo), TGT_HOST, TGT_DB (required), PG_USER (default setu), SETU_SERVICE_DB_PASSWORD (the login's
# password, default the development one), ROLES_SQL (default /service-roles.sql).
set -euo pipefail

service="${1:?usage: provision-service-db.sh <service> <schema[,schema...]>}"
schemas_csv="${2:?usage: provision-service-db.sh <service> <schema[,schema...]>}"
src_host="${SRC_HOST:-postgres}"
src_db="${SRC_DB:-setu_demo}"
tgt_host="${TGT_HOST:?TGT_HOST is required}"
tgt_db="${TGT_DB:?TGT_DB is required}"
pg_user="${PG_USER:-setu}"
role_password="${SETU_SERVICE_DB_PASSWORD:-setu_dev_password}"
roles_sql="${ROLES_SQL:-/service-roles.sql}"

[[ "$tgt_db" =~ ^[a-z][a-z0-9_]*$ ]] || { echo "target database name must be lower-case letters, digits and underscores" >&2; exit 2; }
IFS=',' read -r -a schemas <<< "$schemas_csv"
for s in "${schemas[@]}"; do
  [[ "$s" =~ ^[a-z][a-z0-9_]*$ ]] || { echo "unsafe schema name '$s'" >&2; exit 2; }
done

src() { psql -h "$src_host" -U "$pg_user" -d "$src_db" -X -q -t -A -v ON_ERROR_STOP=1 "$@"; }
tgt() { psql -h "$tgt_host" -U "$pg_user" -d "$tgt_db" -X -q -t -A -v ON_ERROR_STOP=1 "$@"; }
tgt_server() { psql -h "$tgt_host" -U "$pg_user" -d postgres -X -q -t -A -v ON_ERROR_STOP=1 "$@"; }

for host in "$src_host" "$tgt_host"; do
  for _ in $(seq 1 60); do pg_isready -h "$host" -U "$pg_user" -q && break; sleep 2; done
  pg_isready -h "$host" -U "$pg_user" -q || { echo "$host is not accepting connections" >&2; exit 1; }
done

echo "== $service: ${schemas[*]} from $src_host/$src_db to $tgt_host/$tgt_db"
if [[ "$(tgt_server -c "SELECT 1 FROM pg_database WHERE datname = '$tgt_db'")" != "1" ]]; then
  tgt_server -c "CREATE DATABASE $tgt_db" >/dev/null
fi

loaded="$(tgt -c "SELECT to_regclass('public.event_publication') IS NOT NULL")"
if [[ "$loaded" == "t" ]]; then
  echo "== already loaded: only the login's grants are re-applied"
else
  # a database that holds part of the load is not one this script made: refuse rather than guess
  stray="$(tgt -c "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ANY (string_to_array('${schemas_csv},governance', ','))")"
  if [[ "$stray" != "0" ]]; then
    echo "$tgt_db has some of the schemas but is not fully loaded; drop it and run again" >&2
    exit 3
  fi
  for ext in $(src -c "SELECT extname FROM pg_extension WHERE extname <> 'plpgsql'"); do
    tgt -c "CREATE EXTENSION IF NOT EXISTS $ext" >/dev/null
  done
  echo "== loading (one transaction)"
  {
    for s in "${schemas[@]}"; do
      pg_dump -h "$src_host" -U "$pg_user" -d "$src_db" -n "$s" --no-owner --no-privileges
    done
    pg_dump -h "$src_host" -U "$pg_user" -d "$src_db" -n governance --schema-only --no-owner --no-privileges
    pg_dump -h "$src_host" -U "$pg_user" -d "$src_db" -t public.event_publication --schema-only --no-owner --no-privileges
  } | psql -h "$tgt_host" -U "$pg_user" -d "$tgt_db" -X -q -v ON_ERROR_STOP=1 --single-transaction -f - >/dev/null
fi

echo "== verifying"
status=0
for s in "${schemas[@]}"; do
  for t in $(src -c "SELECT tablename FROM pg_tables WHERE schemaname = '$s' ORDER BY 1"); do
    a="$(src -c "SELECT count(*) FROM $s.$t")"
    b="$(tgt -c "SELECT count(*) FROM $s.$t")"
    [[ "$a" == "$b" || "$loaded" == "t" ]] || { echo "MISMATCH $s.$t: source $a, copy $b" >&2; status=1; }
  done
done
allowed="'information_schema','public','governance'"
for s in "${schemas[@]}"; do allowed+=",'$s'"; done
foreign="$(tgt -c "SELECT count(*) FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%' AND nspname NOT IN ($allowed)")"
[[ "$foreign" == "0" ]] || { echo "the copy holds a schema that is not the service's" >&2; status=1; }
[[ "$status" == "0" ]] || exit 1

echo "== the service's login"
tgt -c "SET setu.role_password = '$role_password'" -f "$roles_sql" >/dev/null
echo "== done: $service has its own database"
