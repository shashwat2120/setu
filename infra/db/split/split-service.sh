#!/usr/bin/env bash
# Moves one service's tables out of the monolith's database into a database of its own (Sprint 6B.2, docs/contracts/data-ownership.md).
#
#   split-service.sh <Service> <target-database> [source-database]
#
# <Service> is an owner name from backend/src/test/resources/table-owners.txt (Identity, Academic, Assessment, Attendance, Learning, ...).
# The source database is only ever read. The target must not exist yet, so a half-finished split can never be mistaken for a finished one:
# drop it and run again. What is copied is exactly the schemas that service owns (a table's owner is its schema), schema first, then data,
# then the Flyway history, so the service's own Flyway run starts from the same version as the monolith and applies only what comes after.
# A failed split drops its own target again. It finishes by comparing a row count of every copied table with the source and exits non-zero on any difference.
#
# SETU_SPLIT_EXCLUDE=schema[,schema...] leaves schemas out of the copy. Needed today for Assessment: table-owners.txt gives it the `record`
# schema, but Record still runs in the core and keeps its foreign keys into Academic's tables (docs/contracts/data-ownership.md section 2), so
# `SETU_SPLIT_EXCLUDE=record split-service.sh Assessment ...` is the honest split until Record is extracted. Without it that split fails.
#
# Needs Docker and the compose Postgres. Override with SETU_PG_CONTAINER, SETU_PG_USER, SETU_PG_PASSWORD, SETU_PG_SOURCE_DB.
set -euo pipefail

service="${1:?usage: split-service.sh <Service> <target-database> [source-database]}"
target="${2:?usage: split-service.sh <Service> <target-database> [source-database]}"
source_db="${3:-${SETU_PG_SOURCE_DB:-setu}}"
container="${SETU_PG_CONTAINER:-setu-postgres-1}"
pg_user="${SETU_PG_USER:-setu}"
pg_password="${SETU_PG_PASSWORD:-setu_dev_password}"
owners="$(dirname "$0")/../../../backend/src/test/resources/table-owners.txt"

pg() { docker exec -e PGPASSWORD="$pg_password" -i "$container" "$@"; }
psql_db() { local db="$1"; shift; pg psql -v ON_ERROR_STOP=1 -U "$pg_user" -d "$db" -X -q -t -A "$@"; }

[[ "$target" =~ ^[a-z][a-z0-9_]*$ ]] || { echo "target database name must be lower-case letters, digits and underscores" >&2; exit 2; }
[[ "$service" =~ ^[A-Za-z]+$ ]] || { echo "unknown service name '$service'" >&2; exit 2; }

exclude=",${SETU_SPLIT_EXCLUDE:-},"
mapfile -t tables < <(awk -v s="$service" -v ex="$exclude" '!/^#/ && NF >= 2 && $2 == s { split($1, p, "."); if (index(ex, "," p[1] ",") == 0) print $1 }' "$owners" | tr -d '\r')
if [[ ${#tables[@]} -eq 0 ]]; then
  echo "no tables are owned by '$service' in table-owners.txt" >&2
  exit 2
fi
mapfile -t schemas < <(printf '%s\n' "${tables[@]}" | cut -d. -f1 | sort -u)
echo "== $service owns ${#tables[@]} tables in schemas: ${schemas[*]}"

if [[ "$(psql_db "$source_db" -c "SELECT 1 FROM pg_database WHERE datname = '$target'" 2>/dev/null || true)" == "1" ]] \
   || [[ "$(psql_db postgres -c "SELECT 1 FROM pg_database WHERE datname = '$target'" 2>/dev/null || true)" == "1" ]]; then
  echo "target database '$target' already exists; drop it first (DROP DATABASE $target) so a partial split is never reused" >&2
  exit 3
fi

schema_args=()
for s in "${schemas[@]}"; do schema_args+=(-n "$s"); done

echo "== creating $target"
psql_db "$source_db" -c "CREATE DATABASE $target" >/dev/null
# a split that fails leaves nothing behind that could be mistaken for a finished one
finished=0
cleanup() {
  if [[ $finished -ne 1 ]]; then
    echo "removing the unfinished target database $target" >&2
    psql_db "$source_db" -c "DROP DATABASE IF EXISTS $target WITH (FORCE)" >/dev/null 2>&1 </dev/null || true
  fi
}
trap cleanup EXIT
# the same extensions as the source (types such as citext and operator classes such as btree_gist are used by the owned tables)
while read -r ext; do
  [[ -n "$ext" ]] && psql_db "$target" -c "CREATE EXTENSION IF NOT EXISTS \"$ext\"" >/dev/null </dev/null
done < <(psql_db "$source_db" -c "SELECT extname FROM pg_extension WHERE extname <> 'plpgsql' ORDER BY 1" | tr -d '\r')

echo "== copying structure"
pg pg_dump -U "$pg_user" -d "$source_db" --schema-only --no-owner --no-privileges "${schema_args[@]}" | psql_db "$target" >/dev/null

echo "== copying data"
pg pg_dump -U "$pg_user" -d "$source_db" --data-only --no-owner --no-privileges --disable-triggers "${schema_args[@]}" | psql_db "$target" >/dev/null

echo "== copying the migration history"
pg pg_dump -U "$pg_user" -d "$source_db" --no-owner --no-privileges -t public.flyway_schema_history | psql_db "$target" >/dev/null 2>&1 \
  || pg pg_dump -U "$pg_user" -d "$source_db" --no-owner --no-privileges -t flyway_schema_history | psql_db "$target" >/dev/null

echo "== verifying row counts"
failed=0
for t in "${tables[@]}"; do
  a="$(psql_db "$source_db" -c "SELECT count(*) FROM $t")"
  b="$(psql_db "$target" -c "SELECT count(*) FROM $t")"
  if [[ "$a" != "$b" ]]; then
    echo "MISMATCH $t: source $a, target $b" >&2
    failed=1
  fi
done

echo "== checking the target holds nothing it does not own"
foreign="$(psql_db "$target" -c "SELECT table_schema || '.' || table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema','public') AND table_schema NOT IN ($(printf "'%s'," "${schemas[@]}" | sed 's/,$//')) ORDER BY 1")"
if [[ -n "$foreign" ]]; then
  echo "unexpected tables in $target:" >&2
  echo "$foreign" >&2
  failed=1
fi

echo "== checking no foreign key leaves the owned schemas"
leaving="$(psql_db "$target" -c "SELECT conrelid::regclass || ' -> ' || confrelid::regclass FROM pg_constraint WHERE contype = 'f' AND connamespace::regnamespace::text NOT IN ($(printf "'%s'," "${schemas[@]}" | sed 's/,$//')) ")"
if [[ -n "$leaving" ]]; then
  echo "foreign keys outside the owned schemas:" >&2
  echo "$leaving" >&2
  failed=1
fi

if [[ $failed -ne 0 ]]; then
  echo "SPLIT FAILED for $service -> $target (the source was not changed)" >&2
  exit 1
fi
finished=1
echo "OK: $service copied to $target (${#tables[@]} tables, row counts equal)"
