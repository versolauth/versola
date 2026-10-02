#!/usr/bin/env bash
#
# Asserts that `versola migrate --dry-run` (migrate-tool's checkPending) works
# when there is something to apply -- the one case it exists for.
#
# It used to validate with Flyway's defaults, which report a migration that
# simply hasn't been applied yet as "Detected resolved migration not applied
# to database": every dry run with pending migrations failed on the first
# service. Against that build this fails at step 3.
#
#   1. a fresh database is fully migrated by migrate-tool itself;
#   2. a dry run against it reports every service up to date;
#   3. with the newest auth migration's history row removed (so Flyway sees it
#      as pending), a dry run exits 0 and lists exactly that one migration.
#
# Expects a staged migrate-tool (sbt "migrateTool/stage"), run from the repo
# root (migrate-tool resolves its migrations relative to the working
# directory), and the build job's Postgres on localhost:5432 (user dev).
# Uses a database of its own so the e2e services' `auth` database is untouched.
set -euo pipefail

DB=migrate_dry_run_check
export PGHOST=localhost PGPORT=5432 PGUSER=dev PGPASSWORD=1234
TOOL=migrate-tool/target/universal/stage/bin/migrate-tool

psql -d auth -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"
# One schema per service, created up front as the deploy docs require -- with
# ?currentSchema= pointing at a schema that doesn't exist, the history table
# would not land where each service expects it.
psql -d "$DB" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA auth" -c "CREATE SCHEMA central" -c "CREATE SCHEMA edge"

CONFIG_DIR=$(mktemp -d)
export CONFIG_DIR
for service in auth central edge; do
  cat > "$CONFIG_DIR/$service.conf" <<CONF
postgres {
  url = "jdbc:postgresql://localhost:5432/$DB?currentSchema=$service"
  user = "dev"
  password = "1234"
}
CONF
done

fail() { echo "check-migrate-dry-run: $*" >&2; exit 1; }

echo "== 1. migrate a fresh database"
"$TOOL" || fail "migrate on a fresh database failed"

echo "== 2. dry run, nothing pending"
out=$("$TOOL" --dry-run 2>&1) || { echo "$out"; fail "dry run with nothing pending failed"; }
echo "$out"
for service in auth central edge; do
  grep -q "^$service: up to date" <<<"$out" || fail "$service not reported up to date"
done

echo "== 3. dry run, one auth migration pending"
latest=$(psql -d "$DB" -tAc "SELECT version FROM auth.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
[ -n "$latest" ] || fail "no applied auth migration found"
psql -d "$DB" -v ON_ERROR_STOP=1 -c "DELETE FROM auth.flyway_schema_history WHERE version = '$latest'"

out=$("$TOOL" --dry-run --service auth 2>&1) || { echo "$out"; fail "dry run with a pending migration failed"; }
echo "$out"
grep -q "^auth: 1 pending migration(s):" <<<"$out" || fail "expected exactly one pending auth migration"
grep -q "^  $latest - " <<<"$out" || fail "pending list doesn't name version $latest"

psql -d auth -c "DROP DATABASE $DB" >/dev/null
echo "check-migrate-dry-run: ok"
