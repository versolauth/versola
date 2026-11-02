# Versola

OAuth 2.0 / OpenID Connect identity platform. Scala 3 + ZIO, PostgreSQL (Flyway), OpenTelemetry.
Three services share one codebase: `auth` (the OAuth/OIDC provider), `central` (configuration
store and admin API; reached only through `edge`), `edge` (authenticating reverse proxy and the
admin console's login). `util` is shared code; each service has a `*-postgres-impl` module that
is the runnable app. `central-ui` is the admin SPA (Lit/TypeScript) and also builds the login
forms (Solid.js).

Backward compatibility is the default for every change: HTTP API and error shapes, token and claim
formats, config keys, database schema, CLI flags, stored data. `readme.md` promises none before
1.0.0, but a breaking change is made only when explicitly requested. Otherwise keep the old
behaviour working (add alongside, default to the old value, deprecate before removing) and, if
compatibility cannot be kept, stop and ask. State any break, and how existing deployments migrate,
in the PR and in `deploy.md`.

Read before non-trivial work: `readme.md` (architecture), `develop.md` (running locally,
config generation), `deploy.md` (topology, migrations, secrets), `SECURITY.md`.

# Commands

```bash
sbt compile                              # all services
sbt Test/compile                         # also compiles e2e
sbt test                                 # needs Postgres (serves TLS): docker-compose -f services.yml up -d --build postgres
sbt "auth/testOnly versola.oauth.revoke.RevocationServiceSpec"   # one spec
sbt e2e/test                             # needs the staged stack, see develop.md
cd central-ui && npm run type-check && npm run test:unit   # admin UI; npm run test:ui = Playwright
cd central-ui && npm run build:forms     # required before central is built or staged
```

Scala formatting follows `.scalafmt.conf` (Scala 3, maxColumn 150); keep touched files formatted.
Running a service locally needs `RUN_MIGRATIONS=true` against a fresh database; the exact
commands are in `develop.md`. Do not paste them from memory.

# Definition of Done

A change is done when all of these hold; if one does not, say which and why instead of
reporting the change as finished.

1. `sbt compile` and `sbt test` pass. `sbt test` is run before every commit.
2. Tests exist at the right level (see Test Coverage Rules below).
3. Specs, docs and config that describe the changed behaviour are updated in the same change
   (OpenAPI under `*/open-api/`, `develop.md`/`deploy.md` when run or deploy steps change). The
   product documentation lives in `versolauth/versola-website`: a change to an admin console
   screen needs retaken screenshots, a change to behaviour a standard defines needs its `rfc/`
   page updated or created. The `versola-website` pull request is opened together with the code
   change, never deferred to a follow-up issue. The PR has a **Docs** line (a link to it, or
   `none: <reason>`); see `.claude/rules/documentation.md`.
4. Nothing was reported as tested that was not run. When `sbt e2e/test` cannot run locally,
   say so.

# Working Rules

- Read the code before claiming how it behaves. Never cite an RFC section, config key,
  endpoint or class from memory: open the source, the spec under `*/open-api/`, or the RFC.
- Ask before guessing only when the answer changes what you build and cannot be found in the
  repo. Otherwise pick the conventional option, state it, and proceed.
- Keep a change to one purpose. Do not refactor, reformat or rename unrelated code in it.
- Match the surrounding code: naming, comment density, error style. Comments explain why,
  not what.
- Fix the cause, not the symptom. Do not weaken, skip or delete a failing test to get green;
  if a test is wrong, say why.

# Git

- Default branch is `main`. Work on a feature branch named `feat/...`, `fix/...`,
  `chore/...`; never commit to `main`.
- You may commit and push to a feature branch without asking. Ask first for anything else:
  force-push, merging or rebasing shared branches, tags, releases, deleting branches.
- Commit message: short, imperative, sentence case, says what changes and why it matters
  (`Refuse a repeated or malformed body access_token`). A `module:` prefix is fine
  (`migrations: ...`). No Conventional-Commits `feat:` prefixes.
- Formatting: before every push, `scalafmt --mode diff --diff-branch origin/main` formats the Scala
  files your branch changes; commit the result. `.claude/hooks/pre-push.sh` blocks a push that fails
  `--test` once `.claude/scalafmt-enforced` exists. That file is added by the project-wide reformat
  change, which is a separate pull request: scalafmt works on whole files, so until it lands, do
  not reformat code you did not otherwise touch, and do not run scalafmt over the whole project.
- Hooks in `.claude/hooks/` (registered in `.claude/settings.json`) enforce, among other things:
  no force-push, no push to `main`, no edits of generated forms or of migrations already on
  `origin/main`, and the measured-DB-access rule. A hook that blocks you is right until the operator
  says otherwise; do not work around it.
- Pull request: what changed, which tests were added at which level, any OpenAPI change, and
  any gap you left (missing test level, skipped spec) with the reason.

# Ask Before

- A breaking change of any kind (see the compatibility paragraph above).
- Changing migrations that were already released, or anything under `deploy.md`'s production
  flow.
- Touching secrets, keys, certificates, `SECURITY.md`, `.github/workflows/`, `CODEOWNERS`.
- Adding a dependency to `project/Dependencies.scala`.
- Loosening a security check (redirect URI matching, PKCE, signature or audience validation,
  client authentication) for any reason, including to make a test pass.

# Test Coverage Rules

Every behaviour change ships with both levels of test. Neither substitutes for the other:
a unit test proves the rule, an e2e test proves the rule survives the wiring — registration
through Central's API, auth's configuration cache, HTTP headers and form encoding, the
Postgres round-trip. A rule that is only unit-tested has been proven against stubs.

- **Unit** (`sbt test`) — the module's own `*Spec` next to the code. Stub the collaborators
  the behaviour does not belong to, not the behaviour itself: a service under test gets a
  real instance of the policy it applies and a stub of the repository it reads.
- **E2E** (`sbt e2e/test`) — a spec under `e2e/src/test/scala/versola/e2e/flows/` driving the
  staged `auth`/`central`/`edge` over HTTP. Needed for anything crossing a service boundary,
  a cache, a request header, or a stored column.

When only one level is practical, say which level is missing and why in the PR, and open a
follow-up — do not let the gap pass silently.

Run `sbt test` before every commit. `sbt e2e/test` needs the staged stack (see develop.md);
when it cannot run locally, say so explicitly rather than reporting the change as tested.

# Scala Semantic Rules
If ScalaSemantic MCP is available.

For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then more ScalaSemantic functions can be used with better results.

Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type, signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are available.

Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.

# Scala Import Rules

Do not use fully qualified names in code. Import the class, object, or type and refer to it
by its simple name.

- Wrong: `val id: java.util.UUID = java.util.UUID.randomUUID()`
- Right: `import java.util.UUID` and `val id: UUID = UUID.randomUUID()`

The one exception is a member of an enclosing type, which is referenced through that type
without importing the member itself:

```scala
enum Foo:
  case Bar

val x = Foo.Bar   // not `import Foo.Bar` and `Bar`
```

The same applies to a nested class or object, an `object`'s members, and companion members:
write `Outer.Inner`, not a bare `Inner` imported from `Outer`. Import the outer type, not the
nested one. On a genuine name clash, prefer an import rename (`import a.Foo as AFoo`) over a
full path.

# Scala Construction Rules

When constructing a case class, or calling any method, with three or more parameters, or with two
or more parameters of the same type, pass every argument by name, in declaration order. Positional
arguments are fine only for one- or two-parameter constructors of distinct types, and for tuples.

- Wrong: `ChallengeSettingsRecord(tenantId, phoneRegions, emailRegistration, defaultRoles, ...)`
- Right: `ChallengeSettingsRecord(tenantId = tenantId, phoneRegions = phoneRegions, emailRegistration = ..., ...)`

# Agent instruction loading

`AGENTS.md` is the shared source of project instructions for Codex and Claude Code.
`CLAUDE.md` imports it. Keep project policy here rather than maintaining two copies.
Paths below are relative to the repository root, including when working in a subdirectory.

Before editing, read `.claude/rules/security.md` for every task. Also read every rule whose
`paths` frontmatter matches a file you inspect or change; multiple rules can apply. Rules without
`paths` apply everywhere. Recheck this when the scope grows. Codex must open these files explicitly:
Claude's `.claude/rules` discovery and `@` imports are not Codex instruction loaders.

| Area | Rule file under `.claude/rules/` |
|---|---|
| Scala, sbt, `project/` | `scala-zio.md` |
| Scala logging, errors and metrics | `observability.md` |
| Tests and specs, including frontend | `testing.md` |
| Migrations, repositories, Postgres, migrate-tool, seed data | `database-migrations.md` |
| Auth, edge, client/challenge configuration, OpenAPI | `oauth-oidc-protocols.md` |
| OpenAPI YAML and controllers | `openapi-specs.md` |
| `central-ui/`, including forms and screenshots | `frontend.md` |
| CI, Docker, Kubernetes, local services, scripts, migrate-tool, deployment | `ci-cd-docker-k8s.md` |
| Product code, UI, API, deployment and configuration | `documentation.md` |

The table is an index; the frontmatter in each rule is the authoritative scope. A new rule must
be added to this index so agents that do not discover `.claude/rules/` can find it.

# Codex permissions and guards

`.claude/settings.json` configures Claude Code only. Its permissions and hooks are not
registered with Codex. These equivalent working constraints apply to Codex too:

- Do not read `.local/**`, `.env` or `.env.*`, including through shell commands.
- Do not run `gh release` or the `versola` deployment CLI. Local development commands in
  `develop.md` remain allowed.
- Ask before `git reset --hard`, tagging or recursive forced deletion. Follow the Git and
  Ask Before sections above for other protected actions.
- Never hand-edit generated forms. Never modify an existing migration on `origin/main`
  without explicit authorization; normally add a new migration. Production DB calls use
  `connectMeasured` / `transactMeasured`, with the existing `BasicCodecs.scala` exception.

Run the existing guards from the repository root (Bash and jq required). They consume Claude's
JSON envelope, which can also be supplied by a shell; no Claude installation is needed.
Before each file edit, substitute the actual repository-relative path:

```bash
jq -n --arg path "$PWD/<file>" '{tool_input:{file_path:$path}}' | bash .claude/hooks/guard-paths.sh
```

After editing a production Scala file, run:

```bash
jq -n --arg path "$PWD/<file.scala>" '{tool_input:{file_path:$path}}' | bash .claude/hooks/check-scala-edit.sh
```

Before a push, supply the exact intended push command, for example:

```bash
jq -n --arg command 'git push -u origin chore/example' '{tool_input:{command:$command}}' | bash .claude/hooks/pre-push.sh
```

Inspect the JSON as well as the exit status: `permissionDecision: ask` requires authorization,
`deny` blocks the action even when the script exits zero, and a nonzero exit is a failed check.
Missing jq or scalafmt is not a passing check. Do not bypass a guard with another tool.
These manual checks are working instructions, not a Codex sandbox or an automatic hook install.
