---
name: pre-pr-check
description: Run the project's definition of done on the current branch before a push or pull request, working out from the diff which checks apply, running them, and reporting honestly what passed, what was not run and why. Use before opening or updating a PR, or when asked "is this ready".
---

# Pre-PR check

The goal is a truthful report, not a green one. Never claim something was tested that was not run;
say what you could not run and why. Work from the diff, then run only what applies.

## 1. See what changed

```bash
git fetch origin main
git diff --name-only origin/main...HEAD
git status --short
```

Uncommitted or unrelated changes in the working tree are not yours to include; list them and leave
them out. Group the files: Scala (`*/src/main`, `*/src/test`), migrations, `open-api`, `central-ui`,
`.github`, `docker`/`k8s`, docs.

## 2. Run what applies

| If the diff touches | Run |
|---|---|
| any Scala | `sbt compile`, `sbt Test/compile`, then `sbt test` (needs Postgres: `docker-compose -f services.yml up -d postgres`; `PostgresTlsConnectionSpec` also needs `util/implementations/postgres/tls-fixture/start.sh`) |
| behaviour crossing a service boundary | `sbt e2e/test` (needs the staged stack, `develop.md`); if it cannot run, say so |
| Scala | `scalafmt --mode diff --diff-branch origin/main` and commit the result (see CLAUDE.md "Git" for what to do before the project-wide reformat has landed) |
| `src/main` Scala | no raw `.connect`/`.transact` (the command below the table) |
| migrations | the `add-migration` skill's verify step, including `SutSchemaGuardSpec` |
| `*/open-api/*.yaml` | parse each edited file (any YAML parser; `ruby -ryaml -e 'YAML.load_file(ARGV[0])' <file>`) and check its `$ref`s resolve |
| a controller or DTO | the matching OpenAPI spec was updated, or the PR says why not |
| `central-ui` | from `central-ui/`: `npm run type-check`, `npm run test:unit`, `npx playwright test` |
| a UI change | screenshots were shown to the operator before and after (`frontend.md`) |
| `.github/workflows` | cannot be run locally; say what proves it |
| a security-relevant path (auth, tokens, certificates, sessions) | offer the `security-review` skill |

The raw-database-call check, as CI runs it (no output means clean):

```bash
git grep -nE '\.(connect|transact)[^a-zA-Z_]' -- '**/src/main/**/*.scala' \
  ':(exclude)util/implementations/postgres/src/main/scala/versola/util/postgres/BasicCodecs.scala' \
  | grep -vE '(connect|transact)Measured'
```

## 3. Look at the diff itself

- Anything that is not part of this change (reformatting, renames, drive-by edits)? Remove it.
- Secrets, tokens or real credentials in code, config, tests or docs? (`security.md`)
- A breaking change (API, token/claim shape, config key, schema, CLI flag) that was not asked for?
  (CLAUDE.md compatibility). If it is intended, the PR and `deploy.md` must say how deployments move.
- Hand-kept lists that fail silently, if you added a step, screen, route or permission: see the
  `add-endpoint` and `add-login-screen` skills.
- Every new error path calls `Observability.setError` (`observability.md`).

## 4. Report

Write the result as three lists and put it in the PR description:

- **Ran and passed**: the exact commands.
- **Ran and failed**: with the failing test or message, and whether it also fails on `origin/main`.
- **Not run**: each with the reason (no Postgres, no staged stack, workflow only provable in CI).

Add what the PR needs per CLAUDE.md: what changed, which tests at which level, any OpenAPI change,
and any gap (missing test level, skipped spec) with the reason. Push only to the feature branch, never
force, never to `main`.
