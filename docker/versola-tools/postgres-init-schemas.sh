#!/bin/sh
# Mounted into the bundled Postgres container as /docker-entrypoint-initdb.d/10-schemas.sh (see
# compose.fragment.vps.yml.template). The image runs it once, on the first start with an empty data
# directory, after it has created the superuser (POSTGRES_USER, left unset, so `postgres`) and
# POSTGRES_DB; it never runs again against an existing data directory, so a redeploy cannot
# re-initialise anything. It runs as that superuser over the local socket.
#
# It creates the application role the services connect as (VERSOLA_PG_ROLE, the user in the generated
# configs, already checked by entrypoint.sh to be a plain role name) with the deployment's password,
# gives it the database and the three schemas, and takes the superuser's password away: the superuser
# then logs in over the socket only, from inside the container. The services share one database and
# are isolated by schema (deploy.md, "Database, role and schemas"); each schema belongs to the role so
# Flyway can create its history table there.
#
# `:"role"` and `:'pw'` are psql's own identifier and literal quoting, which is why the values are
# psql variables and not spliced into the SQL text. The password is read by psql itself (`\getenv`)
# from the environment the image got from postgres.secrets.env: it is on no command line, and the
# session does not log a failing statement, since CREATE ROLE's text carries the password.
#
# The image sources a script that is not executable into its own shell, so this sets no shell options
# (`set -eu` would leak into the rest of the image's init); `ON_ERROR_STOP` makes psql fail on an error
# and the image's own entrypoint stops on the failure.

psql -v ON_ERROR_STOP=1 -v role="$VERSOLA_PG_ROLE" -v db="$POSTGRES_DB" -v su="$POSTGRES_USER" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
SET log_min_error_statement = panic;
\getenv pw POSTGRES_PASSWORD
CREATE ROLE :"role" LOGIN PASSWORD :'pw';
\unset pw
ALTER DATABASE :"db" OWNER TO :"role";
CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION :"role";
CREATE SCHEMA IF NOT EXISTS central AUTHORIZATION :"role";
CREATE SCHEMA IF NOT EXISTS edge AUTHORIZATION :"role";
ALTER ROLE :"su" PASSWORD NULL;
SQL
