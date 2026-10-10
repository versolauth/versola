---
paths: ["**/*.scala", "**/*.sbt", "project/**"]
---

# Scala / ZIO Conventions

These are the idioms the existing code follows. Copy a neighbouring file of the same kind
(controller, service, repository) before writing a new one.

## Structure

- Scala 3 with indentation syntax. Formatting is `.scalafmt.conf`; do not hand-format against it.
- A service is a `trait` with a nested `object X: def live: ZLayer[...]` and a `private class Impl`
  (see `RevocationService`). Dependencies come in through the `ZLayer`, not through globals.
- A controller is an `object X extends Controller` with `type Env = ...` listing the services it
  needs and `def routes`. Business logic does not live in the controller.
- Module direction: `util` <- `auth` / `central` / `edge` <- `*-postgres-impl`. A service module
  never depends on its Postgres implementation, and `util` never depends on a service.

## Effects and errors

- Effects are ZIO values. No `Await`, `Unsafe.unsafe` outside the app entry point and tests,
  `.get` on an `Option`/`Either`, `null`, or `var` on shared state.
- Expected failures are values: a domain error ADT per feature (`RevocationError`,
  `IO[Throwable | RevocationError, A]`). `Throwable` is for infrastructure failure. Do not
  swallow a failure with `.ignore` or `catchAll(_ => ZIO.unit)`; if one is deliberately best-effort,
  log it with `ZIO.logWarningCause` and say why in a comment.
- Blocking calls go through `ZIO.attemptBlocking`.

## Things that must go through the project's own services

- **Time:** `Clock.instant` / ZIO `Clock`, never `Instant.now` or `System.currentTimeMillis`.
  Tests rely on `TestClock`.
- **Randomness for anything security-relevant:** the `SecureRandom` service, never
  `scala.util.Random`.
- **Secret material:** the `Secret` type (`versola.util`) for keys, MAC inputs and passwords, so
  it is not printed by accident. Never put a `Secret`, token, password or client secret into a log
  line, an error message or a metric label.
- **Database access in `src/main`:** `.connectMeasured` / `.transactMeasured`, never raw
  `.connect` / `.transact`. CI fails the build on the raw call (it feeds the
  `db_client_operation_duration_seconds` metric).
- **Logging:** no `println`. Request outcomes go through `Observability` (`setError`, `setAuth`
  ...), not extra log lines; see `observability.md`.

## Dependencies

- A new library goes in `project/Dependencies.scala`, grouped like its neighbours. Ask first
  (see CLAUDE.md "Ask Before").
- Compile-scope wiring between modules uses `% CompileTest` as in `build.sbt`; follow it.
