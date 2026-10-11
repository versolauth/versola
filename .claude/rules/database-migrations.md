---
paths: ["**/migrations/**", "**/*Repository.scala", "**/*Postgres*.scala", "migrate-tool/**", "loadgen/src/main/resources/seed/**"]
---

# Database and Migrations

PostgreSQL, Flyway, Magnum for queries. Each service owns its schema and its migrations:

| Service | Directory | Versions |
|---|---|---|
| auth | `auth/implementations/postgres/migrations/` | `V0001`... |
| central | `central/implementations/postgres/migrations/` | `V1001`... |
| edge | `edge/implementations/postgres/migrations/` | `V2001`... |

Name: `V<next number in that range>__snake_case_description.sql`. One schema per service
(`?currentSchema=`); never reference another service's tables.

## A released migration is immutable

- Never edit, rename, reorder or delete a migration that may have been applied outside your
  machine. Flyway checksums it, and an edited one leaves no way forward except recreating the
  schema (`deploy.md` 9.1; this happened in September 2026). Change the schema with a **new**
  migration file.
- The October 2026 squash (`ALTER TABLE` folded into the `CREATE TABLE` migrations) was a one-off,
  deliberate, breaking operation. Do not squash, fold or renumber migrations unless asked to.
- Check: `git diff --name-status <old>..<new> -- '*/postgres/migrations'` shows only `A` lines.

## Writing a migration

- **No `DEFAULT` on columns unless compatibility needs one.** By default the code that inserts
  supplies every value (a constant lives in the Scala model, e.g.
  `EdgeRecord.DefaultRequireDpopNonce`), and raw-SQL test fixtures name every column they insert.
  Add a `DEFAULT` when it is what keeps things working: a new `NOT NULL` column on a table that has
  rows, or one the previous release's `INSERT`s do not name while it keeps serving during
  `versola migrate`. Say in a comment why the default is there and when it can go.
- **Compatible with the previous release.** The old version keeps serving while `versola migrate`
  runs, and rollback to it must still work on the new schema. So: add columns nullable or fed by the
  new code, never drop or rename a column or table the previous release reads in the same release
  (add the new, ship, then remove the old in a later release), no type narrowing, no new `NOT NULL`
  on an existing column without a backfill that finishes inside the migration.
- **No down migrations.** Migrations cannot be rolled back; the pre-migration `pg_dump` is the
  rollback (`deploy.md` 4). So think about the destructive step before writing it, and say in the PR
  if a migration cannot be undone.
- Statements that take long locks or rewrite a table (index on a large table, type change) are
  called out in the PR: the old version keeps serving while they run.
- Comment the non-obvious: why a column exists, what an outbox row means, what a partial index
  serves, as the existing files do.

## Keeping the rest in step

- **Loadgen seeder fingerprint.** `loadgen/src/main/resources/seed/sut-migrations.sha256` pins the
  names and contents of the migration directories the seeder is coupled to. Any change to a
  migration fails `SutSchemaGuardSpec` on purpose. Check whether the seeder's inserts still fit the
  schema, and only then re-record the "found" values in that file. Do not re-record just to get
  green.
- **Repositories and models:** a new column is added to the Scala record, to every `INSERT`
  naming columns, and to the repository spec that holds all implementations to the behaviour.
- **Tests run real migrations** (`PostgresSpec` has `migrate = true`), so repository specs prove the
  migration itself. `sbt test` needs Postgres up.
- **`migrate-tool`:** CI runs `.github/scripts/check-migrate-dry-run.sh`; if you change the tool or
  how migrations are resolved, run it against the staged tool.
- **Startup behaviour:** services validate the schema and do not migrate unless
  `RUN_MIGRATIONS=true`; production migrates with the separate `versola migrate` step. Do not
  change that default, and do not make a service apply a migration implicitly.

## Docs

A change that alters what an operator must do (a new schema, a data step before upgrade, a break
like the squash) updates `deploy.md` in the same change.
