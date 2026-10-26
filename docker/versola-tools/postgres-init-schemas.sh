#!/bin/sh
# Mounted into the bundled Postgres container as /docker-entrypoint-initdb.d/10-schemas.sh (see
# compose.fragment.vps.yml.template). The image runs it once, on the first start with an empty data
# directory, after it has created POSTGRES_USER and POSTGRES_DB itself; it never runs again against
# an existing data directory, so a redeploy cannot re-initialise anything. It runs as the superuser
# over the local socket, so the password is not needed here and does not appear in this file.
#
# The three services share one database and are isolated by schema (deploy.md, "Database, role and
# schemas"); each schema belongs to the application role so Flyway can create its history table there.
# `:"role"` is psql's own identifier quoting, which is why the name is passed as a variable and not
# spliced into the SQL text.
set -eu

psql -v ON_ERROR_STOP=1 -v role="$POSTGRES_USER" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION :"role";
CREATE SCHEMA IF NOT EXISTS central AUTHORIZATION :"role";
CREATE SCHEMA IF NOT EXISTS edge AUTHORIZATION :"role";
SQL
