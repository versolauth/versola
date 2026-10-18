#!/usr/bin/env bash
#
# Asserts that `versola migrate --dry-run` (migrate-tool's checkPending) works
# when there is something to apply -- the one case it exists for. Steps 3-6 cover
# what the chart's migration Job relies on (#209): several --service flags,
# only the postgres block having to resolve, and a lock held by the previous
# version's traffic being waited out instead of queued behind.
#
# It used to validate with Flyway's defaults, which report a migration that
# simply hasn't been applied yet as "Detected resolved migration not applied
# to database": every dry run with pending migrations failed on the first
# service. Against that build this fails at step 7.
#
#   1. a fresh database is fully migrated by migrate-tool itself;
#   2. a dry run against it reports every service up to date;
#   3. several --service flags select exactly those services;
#   4. only the postgres block has to resolve;
#   5. a lock held for a moment is waited out (lock_timeout plus retries);
#   6. with no retries left the same lock fails the run;
#   7. with the newest auth migration's history row removed (so Flyway sees it
#      as pending), a dry run exits 0 and lists exactly that one migration.
#
# Expects a staged migrate-tool (sbt "migrateTool/stage"), run from the repo
# root (migrate-tool resolves its migrations relative to the working
# directory), and the build job's Postgres on localhost:5432 (user dev;
# PG_PORT overrides the port for a local run beside another Postgres).
# Uses a database of its own so the e2e services' `auth` database is untouched.
set -euo pipefail

DB=migrate_dry_run_check
PG_PORT=${PG_PORT:-5432}
export PGHOST=localhost PGPORT=$PG_PORT PGUSER=dev PGPASSWORD=1234
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
  url = "jdbc:postgresql://localhost:$PG_PORT/$DB?currentSchema=$service"
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

echo "== 3. several --service flags select exactly those services"
out=$("$TOOL" --dry-run --service edge --service auth 2>&1) || { echo "$out"; fail "dry run with two --service flags failed"; }
echo "$out"
grep -q "^auth: up to date" <<<"$out" || fail "auth was not checked"
grep -q "^edge: up to date" <<<"$out" || fail "edge was not checked"
! grep -q "^central:" <<<"$out" || fail "central was checked though it was not selected"
"$TOOL" --dry-run --service auth --service nope >/dev/null 2>&1 && fail "an unknown --service was accepted"

echo "== 4. only postgres.* has to resolve"
# What a Job that was handed the database password and nothing else sees: the same files, with
# a placeholder elsewhere in them that nothing defines.
cat > "$CONFIG_DIR/auth.conf" <<CONF
postgres {
  url = "jdbc:postgresql://localhost:$PG_PORT/$DB?currentSchema=auth"
  user = "dev"
  password = \${?MIGRATE_CHECK_PG_PASSWORD}
}
access-tokens-secret = \${MIGRATE_CHECK_NEVER_SET}
CONF
out=$(MIGRATE_CHECK_PG_PASSWORD=1234 "$TOOL" --dry-run --service auth 2>&1) || { echo "$out"; fail "an unrelated unresolved placeholder broke the run"; }
grep -q "^auth: up to date" <<<"$out" || fail "auth not reported up to date with an unrelated placeholder unset"
# ...while the password itself still has to be there, and the error names the key, not the value.
out=$("$TOOL" --dry-run --service auth 2>&1) && fail "a run without the database password succeeded"
grep -q "postgres.password" <<<"$out" || { echo "$out"; fail "the error does not name postgres.password"; }

echo "== 5. a lock held for a moment is waited out"
for service in auth; do
  cat > "$CONFIG_DIR/$service.conf" <<CONF
postgres {
  url = "jdbc:postgresql://localhost:$PG_PORT/$DB?currentSchema=$service"
  user = "dev"
  password = "1234"
}
CONF
done
hold_lock() {
  psql -d "$DB" -v ON_ERROR_STOP=1 -q -c "BEGIN; LOCK TABLE auth.flyway_schema_history IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep($1); COMMIT" >/dev/null &
  sleep 1
}
hold_lock 4
out=$(MIGRATE_LOCK_TIMEOUT_MS=1000 MIGRATE_LOCK_RETRIES=5 "$TOOL" --service auth 2>&1) || { echo "$out"; fail "the migration did not survive a lock held for 4s"; }
echo "$out"
grep -q "a lock was not available" <<<"$out" || fail "no retry was reported, so the lock was never contended"
wait

echo "== 6. with no retries left, a held lock fails the run"
hold_lock 4
MIGRATE_LOCK_TIMEOUT_MS=1000 MIGRATE_LOCK_RETRIES=0 "$TOOL" --service auth >/dev/null 2>&1 && fail "a held lock did not fail a run without retries"
wait

# Last, because it leaves the auth history without its newest row for good.
echo "== 7. dry run, one auth migration pending"
latest=$(psql -d "$DB" -tAc "SELECT version FROM auth.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
[ -n "$latest" ] || fail "no applied auth migration found"
psql -d "$DB" -v ON_ERROR_STOP=1 -c "DELETE FROM auth.flyway_schema_history WHERE version = '$latest'"

out=$("$TOOL" --dry-run --service auth 2>&1) || { echo "$out"; fail "dry run with a pending migration failed"; }
echo "$out"
grep -q "^auth: 1 pending migration(s):" <<<"$out" || fail "expected exactly one pending auth migration"
grep -q "^  $latest - " <<<"$out" || fail "pending list doesn't name version $latest"

psql -d auth -c "DROP DATABASE $DB" >/dev/null
echo "check-migrate-dry-run: ok"
