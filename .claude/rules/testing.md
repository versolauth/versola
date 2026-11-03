---
paths: ["**/src/test/**", "e2e/**", "**/*Spec.scala", "central-ui/tests/**", "central-ui/**/*.test.ts"]
---

# Testing Conventions

CLAUDE.md's "Test Coverage Rules" say *what* must be tested. This file says *how*.

## Unit specs (ZIO Test + scalamock-stubs)

- `object XSpec extends UnitSpecBase` (`UnitSpecBase` mixes in `ZIOSpec` and `ZIOStubs`).
  Layout: `val spec = suite("X")(suite("method")(test("what happens") { ... }))`.
- Name a test for the behaviour and its condition: `"fail with InvalidClient when token belongs
  to a different client"`, not `"test2"`.
- Stub collaborators with `stub[T]`, then assert on interaction with `stub.method.calls`
  (e.g. `.calls.isEmpty`, `.calls == List(...)`). Stub the repository, not the policy under test.
- Time comes from `TestClock`; do not sleep in a unit test.
- Every security rule gets a negative test next to the positive one: the wrong client, wrong
  audience, expired, replayed, malformed. A rule with only a passing test is not tested.
- Shared constants (ids, secrets, clients) go at the top of the object, like the neighbouring specs.

## Repository specs (one behaviour spec, one runner per storage)

A repository is a trait with a storage behind it, and the storage may change. So the spec of a
repository is written once, against the trait, and a storage only runs it. **Never write
`object PostgresXRepositorySpec extends PostgresSpec` with the tests inside**: that spec cannot
be run against another storage, and the next storage would have to copy it.

- The behaviour lives in `trait XRepositorySpec extends DatabaseSpecBase[XRepositorySpec.Env]`
  in the service module's `src/test` next to the trait it specifies (`auth/src/test/.../
  VelocityRepositorySpec.scala`). `Env(repository: XRepository, ...)` is what a case gets;
  `testCases(env)` returns the cases, written against the trait only (see
  `ChallengeThrottleRepositorySpec`, `VelocityRepositorySpec`).
- The runner lives in the storage module: `object PostgresXRepositorySpec extends PostgresSpec,
  XRepositorySpec` supplies `environment` (the real repository) and `beforeEach` (reset the tables,
  insert nothing the case does not ask for). It has no test bodies of its own beyond the two kinds
  below.
- A case that needs rows the repository under test does not write (the rollup's statistics, an
  earlier proposal, a list entry) asks for them through a `Fixtures` trait in `Env`, which the
  runner implements with SQL (see `AntifraudProposalRepositorySpec.Fixtures`). The case never
  holds a transactor and never writes SQL.
- What only one storage promises (a `statement_timeout`, giving way to an advisory lock, a
  partition kept ahead, a row of an unknown kind skipped) goes in the runner's
  `implementationCases(env)`, which may use the transactor. Everything else is behaviour and goes
  in the trait.
- New repository behaviour is added to the trait, so every storage is held to it; a new storage
  is a new runner of a few lines, not a new spec.
- `PostgresSpec` connects to `POSTGRES_HOST` (default `localhost:5432`) and runs migrations; each
  test resets state in `beforeEach` with `TRUNCATE ... CASCADE` or `DELETE` and inserts only what
  it needs.
- Migrations run for real in these specs, so a broken migration fails here first.

## E2E specs

- Under `e2e/src/test/scala/versola/e2e/flows/<area>/`, `object XFlowSpec extends E2ESpec`.
  Reuse `Flows`, `OAuthClient`, `CentralApi`, `EdgeApi` and `Fixtures` from `e2e/.../support/`
  before adding a new helper.
- Test through HTTP only. Do not reach into a service's internals or write to its tables to set
  up a case unless a `support` helper already does that for the same purpose.
- Where state propagates asynchronously (auth -> edge back-channel, config cache refresh), poll
  for the expected state with a bound; never a fixed `sleep`. State in a comment what is awaited.
- `sbt e2e/test` needs the staged stack (`develop.md`); `LoadgenProvisionSpec` is run first in CI so
  provisioning breakage is reported separately.

## Frontend (`central-ui`)

- `npm run test:unit` (Vitest) for logic, `npm run test:ui` (Playwright) for flows;
  `npm run type-check` must pass.

## Keeping tests honest

- A test that fails is a finding, not an obstacle. Do not delete, skip, loosen an assertion or
  add retries to make it pass; find out whether the code or the test is wrong, and say which.
- A flaky test is a bug to be fixed at its cause (usually an unawaited async step), not rerun
  until green.
- When you could not run a level of test, the final report says so (CLAUDE.md, Definition of Done).
