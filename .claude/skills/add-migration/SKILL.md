---
name: add-migration
description: Add a database migration (new table, column, index or trigger) for auth, central or edge, with the compatibility, loadgen-fingerprint, repository and test steps this project needs. Use when a change needs a schema change, or when asked to alter, add or drop a column or table.
---

# Add a migration

Read `.claude/rules/database-migrations.md` first; this is the procedure. The point of every rule
there: a migration already applied somewhere can never be edited, the previous release keeps serving
while `versola migrate` runs, and a migration cannot be rolled back.

## 1. Check that you need one, and that it is compatible

- Prefer a nullable column, or one the new code fills in, to a change of an existing column.
- **Never** drop or rename a column/table the previous release reads, in the same release. Ship the
  new one, stop using the old one, remove it in a later release.
- No new `NOT NULL` on an existing column without a backfill that finishes inside the migration. A
  `DEFAULT` is allowed only where compatibility needs it (rows already exist, or the previous release's
  `INSERT`s do not name the column); comment why and when it can go.
- If a destructive step is unavoidable or the break cannot be avoided, stop and ask the operator
  (CLAUDE.md "Ask Before").

## 2. Write the file

- Directory and range: `auth/implementations/postgres/migrations/` (`V0001`...),
  `central/implementations/postgres/migrations/` (`V1001`...), `edge/implementations/postgres/migrations/`
  (`V2001`...). List the directory, take the next number, name it `V<NNNN>__snake_case.sql`.
- Never reference another service's tables. Comment what a column or index serves.
- A central table whose rows are cached by auth/edge also needs a notify trigger and the sync chain
  (`add-endpoint` skill, step 3; the pattern is `V1009__notify_triggers.sql`).
- Never edit an existing file; the hook asks for confirmation on any migration that exists on
  `origin/main`.

## 3. Keep the code in step

- Scala record/model, every `INSERT` that names columns, the Postgres repository, and the abstract
  repository spec (`XRepositorySpec`, so every implementation is held to it). Raw-SQL fixtures in tests
  name every column they insert.
- Services only validate the schema unless `RUN_MIGRATIONS=true`; do not change that.

## 4. The loadgen fingerprint (fails on purpose)

`loadgen/src/main/resources/seed/sut-migrations.sha256` pins the migration directories of auth and
central. Any change fails `SutSchemaGuardSpec`:

```bash
sbt "loadgen/testOnly versola.loadgen.seed.SutSchemaGuardSpec"
```

Do not just copy the new hash. Answer the question in that file's header: does
`versola.loadgen.seed.SutSchema` still describe every column a seeded user needs, and does `SeedRows`
still write a value for each? Fix the seeder if not, then record the "found" values from the failure.

## 5. Verify

- `sbt test` with Postgres up (`docker-compose -f services.yml up -d postgres`): `PostgresSpec` runs
  the real migrations, so repository specs prove the migration.
- If you touched `migrate-tool` or how migrations are resolved: stage it and run
  `.github/scripts/check-migrate-dry-run.sh` (see its header for the setup).
- `git diff --name-status origin/main -- '*/postgres/migrations'` shows only `A` lines.

## 6. Say it

In the PR: whether the migration can be undone (it cannot be rolled back; the pre-migration `pg_dump`
is the rollback), any long lock or table rewrite, and the compatibility reasoning. Update `deploy.md`
if an operator must do something (a data step, a break).
