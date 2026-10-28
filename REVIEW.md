# Code Review Guide

Instructions for a reviewer (human or Claude) of a pull request to Versola. Versola is an identity
provider: its hot paths (`/token`, `/authorize`, refresh, introspection, session resume) run on every
login of every user, and a mistake is a vulnerability or an outage, not just a bug.

Project rules live in `CLAUDE.md` and `.claude/rules/*.md`. This file does not repeat them; it says
**how to review** against them and what to weigh first. When a rule there is violated, cite the file.

## Principles

1. **Review the diff against the base branch, not the description.** Read the changed code and the
   code around it. Verify claims ("tested", "no behaviour change") against the source.
2. **Report only what you can point at.** Every finding names a `file:line`, a concrete failing
   scenario (input/state -> wrong result, N queries, lock held, leak), and a suggested fix. No
   "consider", no style taste the formatter or `.scalafmt.conf` already decides.
3. **Rank by blast radius.** Correctness, security and data loss first; DB cost and scalability
   next; readability and tests after. Do not bury a blocker under ten nits; cap nits at three.
4. **Read the whole path, not the hunk.** A new call in a hot path is judged by how often the path
   runs, not by how small the diff is. Follow the call from controller to repository.
5. **Silence is fine.** If nothing is wrong, say "No findings" and list what you checked. Do not
   invent issues to look thorough.

## Review order

Go in this order and stop to flag a blocker as soon as you find one.

0. **Shape**: is this the right, cheapest way to solve it (section 0)? Do it first; a wrong shape
   makes the line-level review moot
1. **Compatibility and data safety** (migrations, API, stored data, config)
2. **Security**
3. **Correctness and concurrency**
4. **Database access and scalability** (the section below; weigh it heavily)
5. **Tests**
6. **Readability, structure, observability**

## 0. Is this the right shape?

Do this before reading the code line by line. A correct implementation of the wrong shape is still a
cost: every new public surface (endpoint, query parameter, claim, cookie, column, config key, error
code, CLI flag) is a compatibility promise (CLAUDE.md "Backward compatibility") that needs an OpenAPI
entry, `deploy.md` text, unit and e2e tests, observability, rate-limit and authorization review, and a
deprecation path to ever remove. Prefer the change that adds the least of it.

### Method

1. **State the problem in one sentence**, from the linked issue, not from the diff.
2. **Find what already serves it.** Search before judging: controllers and `*/open-api/` for the same
   resource, actor and verb; the sibling flow that solved a neighbouring problem (web vs native, `auth`
   vs `edge`, an existing cache, helper, table, cleanup job or `Observability` code). Never conclude
   "nothing exists" without having searched.
3. **List the alternatives**, always including "extend the existing X additively" and "no new state /
   no new surface (derive it from data the request or row already carries; use a mechanism the
   standard already defines)". Compare them on: public surface added, new stored state, DB round-trips
   on a hot path, compatibility risk, test and doc cost, security strength. The security column is a
   veto, not a weight: do not propose a cheaper shape that is weaker.
4. **Choose the cheapest shape that is not weaker.** If the PR did not, say which alternative and what
   it saves, in concrete terms (file/function, "removes endpoint X", "saves a column and a migration").
   If the PR chose the same shape, say "alternatives considered, current shape kept" and why, in one line.

### Extend, do not add, when they are coupled

A new endpoint, table, service or flag is justified only by a real difference. If the new behaviour has
the **same** caller, authentication scheme, resource, lifecycle, transaction and failure semantics as
an existing one, extend it:

- An optional request field/parameter, a new field in a response, or a new value of an existing enum,
  all additive, rather than a second endpoint that repeats the same validation, lookup and auth.
- A column on the existing row rather than a table with the same key and the same lifecycle (no new
  join, no second insert, one cleanup path).
- The existing cache, background loop or cleanup job rather than a parallel one.
- The existing error code, metric and config key (reuse before inventing).

### A new surface is justified when the difference is real

- **Different caller or authentication**: end user vs admin vs service-to-service vs mTLS client; the
  endpoint must not be reachable by whoever may call the existing one.
- **Different contract owner**: a standard endpoint (`/token`, `/authorize`, `/par`, `/userinfo`,
  introspection) stays RFC-shaped. Non-standard parameters or modes go to a separate endpoint or an
  extension the RFC defines, never smuggled into it.
- **Different safety or caching semantics**: safe/idempotent GET vs state-changing POST, or a
  response that must or must not be cached.
- **Different performance or abuse profile**: a cold or expensive path must not add cost to a hot one
  (an extra query on `/token` for a rarely used feature), and needs its own rate limit.
- **Different lifecycle or retention**: separate cardinality, TTL or cleanup (a new table, not a
  column), or a column that would become a hot row.
- **Incompatible shape**: it cannot be added without a breaking change to the existing one.

### Do not overload either

Extending is wrong when it makes one endpoint do two jobs: a boolean or mode parameter that forks the
behaviour (see section 6), a body whose meaning depends on which optional fields are present, or a
hot-path handler that now branches on a cold-path feature. Coupled means "one reason to change", not
"nearby".

### Red flags

- A new endpoint whose handler is mostly a copy of an existing one (same lookup, same checks, one
  different final step).
- A new table or column keyed by the same id as an existing one, written in the same request.
- A new endpoint/parameter/column/config key with no consumer in the PR, or whose consumer is the
  same service that could call the code directly.
- A custom token, nonce, signature or header scheme where the standard already has a parameter for it.
- A second implementation of a rule that exists in a sibling flow: the two will drift. Reuse it, or
  flag that they now differ.
- Stored state added where the value could be recomputed from what the request already carries.
  (Stored state is still right when it is the security property, for example a hash that a
  read-only database leak must not turn into a usable credential. Say that it is, not that it is
  merely convenient.)

### How to report it

A shape finding is **MAJOR** when it duplicates an existing surface or adds a public one that is not
needed (it is permanent and costly to remove), **MINOR** when the cost is internal and reversible.
Respect a decision the linked issue already took and argued: raise the alternative once, as a question
or a note, and do not block on it unless the code shows the issue's premise is wrong. An alternative
must be concrete and no weaker than the PR's; "consider a different design" is not a finding.

## 1. Compatibility and data safety

- Breaking changes (HTTP API and error shapes, token/claim formats, config keys, schema, CLI flags,
  stored data) are blockers unless the PR says they were explicitly requested and documents the
  migration in `deploy.md`. See CLAUDE.md "Backward compatibility".
- **Migrations** (`.claude/rules/database-migrations.md`):
  - A migration already on `origin/main` is never edited, renamed, reordered or deleted.
  - Compatible with the previous release: old code keeps serving while `versola migrate` runs and
    must still work after rollback. No drop/rename of something the previous release reads, no new
    `NOT NULL` on an existing column without a backfill that finishes in the migration, no type
    narrowing.
  - Long locks: an index on a large table, a table rewrite, a `NOT NULL`/`DEFAULT` change that
    rewrites, an `ALTER` taking `ACCESS EXCLUSIVE` while serving. Is it called out in the PR? Is
    there a lock-free alternative (add nullable, backfill in batches, then constrain)?
  - `DEFAULT` on a column only when compatibility needs it, with a comment saying why.
  - Version number is the next free one in the service's range (auth `V0001`, central `V1001`,
    edge `V2001`); no cross-service table references.
  - The loadgen seeder fingerprint was reviewed, not blindly re-recorded.
- New columns reach the Scala record, every column-naming `INSERT`, and the abstract repository spec.

## 2. Security

Fail closed. Anything that weakens a check is a blocker without an explicit, justified exception
(CLAUDE.md "Ask Before").

- Redirect URI matching, PKCE, signature/audience/issuer validation, client authentication, DPoP,
  `state`/`nonce`/CSRF handling: not loosened, not made optional, not skipped on an error path.
- **Every security-relevant branch has a negative test** next to the positive one (wrong client,
  wrong audience, expired, replayed, malformed).
- **Replay and single-use:** codes, refresh tokens, PAR requests, DPoP proofs and nonces are consumed
  atomically (one `UPDATE ... WHERE used = false RETURNING`, or `DELETE ... RETURNING`), never
  "SELECT then UPDATE" in two statements (TOCTOU double-spend).
- **Secrets:** a `Secret` type for keys, MACs, passwords; never in a log line, error message, metric
  label, URL or response body. No real secrets in code, tests, docs or workflows.
- **Comparison:** constant-time comparison for MACs, tokens, hashes. Randomness for anything
  security-relevant comes from the `SecureRandom` service.
- **Authorization:** the new endpoint authenticates, and checks that the caller owns the resource
  (tenant, client, user), not just that the resource exists. Look for IDOR across tenants/clients.
- **Enumeration and abuse:** error responses and timing do not reveal whether a user/client exists;
  attacker-triggerable work (hashing, DB writes, log lines) is rate-limited or bounded.
- New dependency (needs approval), new workflow permission, unpinned action, `${{ untrusted }}`
  interpolated into `run:`.
- Changes to authentication, token, certificate or session logic: recommend running the
  `security-review` skill.

## 3. Correctness and concurrency

- Edge cases: empty, `None`, duplicates, boundary values, clock skew, expiry at exactly `now`,
  Unicode/case normalization, very large input. Off-by-one in TTLs and pagination.
- **Error handling:** expected failures are typed values (a per-feature error ADT); `Throwable` is for
  infrastructure. No `.ignore`, `catchAll(_ => ZIO.unit)` or swallowed cause without
  `ZIO.logWarningCause` and a comment saying why it is best-effort.
- **Race conditions:** check-then-act on shared state, `Ref` read-modify-write that is not
  `Ref.modify`/`update`, lost updates when two requests touch the same row, a unique constraint
  violation that the code treats as a 500 instead of the domain "already exists/replayed" outcome.
- **ZIO hygiene:** no `Await`, `Unsafe.unsafe`, `.get`, `null`, shared `var`; blocking calls inside
  `ZIO.attemptBlocking`; time through `Clock`, not `Instant.now`; resources released (`acquireRelease`,
  `Scope`); fibers not leaked (`fork` without `forkScoped`/`forkDaemon` reason); `foreachPar` bounded
  (`withParallelism`) when the input size depends on the caller.
- **Idempotency and retries:** an operation that can be retried (client retry, outbox redelivery,
  at-least-once back-channel) produces the same final state.
- **Config reload caches** (`ReloadingCache`): a read sees a consistent snapshot; a failed reload
  keeps the last good value; startup behaviour with an unreachable source is unchanged.
- Dead code, unreachable branches, a result computed and dropped, a `TODO` without an issue link.

## 4. Database access and scalability

This is where reviews most often miss regressions. Treat the number of round-trips and the
shape of each query as part of the contract of a change. The hot paths are measured by
`db_client_operation_duration_seconds` (fed by `connectMeasured` / `transactMeasured`) and
exercised by the loadgen campaigns; a change that adds a query per request shows up there as
throughput loss.

### 4.1 Count the queries

For every changed code path, write down (in the review) the number of DB round-trips **before and
after**, per request, for the common case. Flag any increase on a hot path, and any unbounded
growth.

- **N+1:** a query inside `foreach`/`map`/`collect`/a ZIO loop, or a lookup per element of a
  result set. Replace with one query (`WHERE id = ANY(?)`, a `JOIN`, `INSERT ... SELECT`) or a
  batch. Check both the repository and its callers: the loop is often in the service.
- **Chatty read-modify-write:** `SELECT` then `UPDATE` then `SELECT` of the same row. Prefer one
  statement with `RETURNING`, an `INSERT ... ON CONFLICT`, or a conditional `UPDATE`.
- **Redundant reads:** the same row loaded twice in one request (controller and service both look up
  the client/session/user). Pass the value down.
- **Reads on a path that can be cached or avoided:** configuration (clients, tenants, themes,
  scopes) is served from `ReloadingCache`, not from the DB per request. A new per-request read of
  data that changes rarely is a finding; so is a cache with no invalidation story or an unbounded
  size.
- **Work that can leave the request:** audit rows, cleanup, notifications, statistics. Does it need
  to block the response, or can it go through the outbox / a background fiber? A write on the read
  path (a "last seen" `UPDATE` per request) is a hot-row contention risk; throttle or batch it.
- **Fan-out writes:** one logical operation that writes to many rows/tables (revoke a user's
  sessions, delete a tenant): done in bounded batches, not one statement per row from the app.

### 4.2 Each query must be able to use an index

- Every new `WHERE`, `JOIN`, `ORDER BY` column set on a table that grows with users, sessions,
  tokens or events has a matching index in a migration of the same PR. State which index serves it.
- Does the query match the index: leading column present, no function or cast on the indexed column
  (`lower(email)` needs an expression index), the same collation, the same type (no `uuid = text`).
- Partial indexes for "active" subsets (`WHERE revoked_at IS NULL`); no index that nothing reads
  (each index taxes every write).
- `LIKE '%x%'`, `OR` across different columns, `NOT IN` over a subquery, `OFFSET` pagination over a
  large table, `COUNT(*)` on a big table in a request path: each is a finding unless bounded.
- `SELECT *` or fetching large `bytea`/`jsonb` columns that the caller does not use.
- Where the plan is not obvious, ask for `EXPLAIN (ANALYZE, BUFFERS)` against realistic row counts,
  not the empty test database.

### 4.3 Bound everything that grows

- Every list query has a `LIMIT` (and keyset pagination for anything a client can page through).
  An endpoint that returns "all X" for a tenant is unbounded growth waiting for a big customer.
- Tables that accumulate rows (sessions, codes, tokens, conversations, proofs, nonces, events) have
  a retention story: an expiry column, a cleanup job that deletes **in bounded batches** (never one
  huge `DELETE`), an index that makes the expiry scan cheap, and a bound on how many a single
  user/client can create.
- Background loops: bounded batch size, a delay between batches, safe to run on several instances at
  once (`FOR UPDATE SKIP LOCKED`, an advisory lock, or an idempotent statement).

### 4.4 Transactions, locks and connections

- **Transaction scope:** as small as correctness allows. No HTTP call, hashing (Argon2), signing,
  `sleep`, or other slow work while a connection or transaction is held; it starves the pool and
  holds locks. Compute first, then write.
- Multi-statement atomicity uses `transactMeasured`; single statements use `connectMeasured`. Never
  raw `.connect`/`.transact` in `src/main` (CI fails the build).
- A write that must not race uses a unique constraint or a conditional `UPDATE`, and handles the
  conflict as a domain outcome. `SELECT ... FOR UPDATE` is justified, scoped to the minimum rows, and
  takes locks in a consistent order across code paths (deadlock risk).
- **Hot rows:** a counter or "last X" column updated by every request of a tenant/client/user
  serialises those requests on a row lock. Flag it; suggest append + aggregate, sharding, or batching.
- Connection usage: one request does not hold two pool connections at once (nested `connect` inside
  `connect` can deadlock a small pool); parallel fan-out is bounded below the pool size.
- Isolation assumptions are stated where they matter (READ COMMITTED is the default; a rule that
  needs more must say how it gets it).

### 4.5 Write correctness and cost

- Inserts of many rows are batched; upserts use `ON CONFLICT` with an explicit target, not
  catch-the-exception.
- `UPDATE` touches only the changed columns; rewriting a wide `jsonb` or array column on every
  request bloats the table (MVCC dead tuples). Narrow rows and `HOT` updates matter on high-churn
  tables.
- Parameters are bound, never string-interpolated into SQL (`sql"..."` interpolator, not `s"..."`).
  Dynamic `ORDER BY`/column names come from a closed allow-list.
- Time in SQL comes from the application `Clock` parameter, not `now()`, where tests or consistency
  with an in-memory value depend on it (match the surrounding repository).
- `Option` <-> `NULL` mapping and codec round-trips are covered by the abstract repository spec.

### 4.6 Scale-out and caches

- The code works with N instances behind a load balancer: no in-process state that must be shared
  (rate limit counters, "already processed" sets, locks) unless it is explicitly best-effort or
  backed by Postgres.
- In-memory caches have a size bound, a TTL or reload, and a defined behaviour on source failure.
  Per-key caches on attacker-controlled keys (client_id, IP, user agent) are an unbounded-memory
  vector.
- Anything keyed by tenant/client has bounded cardinality or is bounded some other way; metric labels
  and log fields use only closed sets.

### 4.7 Verifying cost

For a change on a hot path, ask for one of: the before/after query count from the test or a
`pg_stat_statements` snapshot, the new `EXPLAIN` plan, or a loadgen comparison against the baseline
(state the observed refresh:code exchange ratio when reporting a campaign). "Should be fast" is not
evidence.

## 5. Tests

`CLAUDE.md` "Test Coverage Rules" and `.claude/rules/testing.md` are the bar.

- A behaviour change ships a **unit** spec and an **e2e** flow spec, or the PR names the missing
  level, why, and links a follow-up. A rule only proven against stubs is not proven.
- Tests fail for the right reason: assert on the outcome and on stub interactions
  (`stub.method.calls`), not on "does not throw". Names state behaviour and condition.
- New repository behaviour lives in the abstract `XRepositorySpec` so every implementation is held to
  it; a migration is proven by the real-Postgres spec.
- No fixed `sleep` in e2e (poll with a bound), no real clock in unit tests (`TestClock`), no weakened,
  skipped or deleted tests to get green, no order dependence between tests.
- Concurrency-sensitive code (single-use tokens, conflict handling) has a test that exercises two
  racing callers, not only the sequential case.
- The PR does not claim "tested" for what was not run (`sbt e2e/test` often cannot run locally;
  the PR must say so).

## 6. Readability, structure, observability

- One purpose per PR; no unrelated refactors, renames or reformatting of untouched code.
- Matches the surrounding code: service = `trait` + `object X: def live` + `private class Impl`;
  controller holds no business logic; module direction `util <- services <- *-postgres-impl`.
- Names say what a thing is; functions do one thing; no boolean-flag parameters that fork behaviour;
  no deep nesting a guard clause would flatten. Comments explain *why*, not *what*.
- **Observability** (`.claude/rules/observability.md`): every new error branch calls
  `Observability.setError` with a code from the closed vocabulary; no new per-request log lines, no
  logging in loops or on the success path, no log-and-rethrow, no secrets or personal data in logs.
- Public surface changes update the OpenAPI under `*/open-api/`, `develop.md`/`deploy.md`, and the
  secret/chart/startup checks in `.github/scripts/` when their inputs change.
- Frontend (`central-ui`): admin console (Lit) and login forms (Solid) keep their own conventions;
  generated `forms/` output is never edited; no secrets or user input in `console.*` or URLs; user
  text goes through i18n; accessibility (the axe scan) is not regressed.
- Commit and PR hygiene: imperative sentence-case messages without `feat:` prefixes; PR description
  states what changed, which tests at which level, any OpenAPI change, and any gap left. When it adds
  a public surface (endpoint, parameter, column, config key, cookie) it says why the existing one could
  not be extended (section 0).

## Output format

Lead with a verdict: **Approve**, **Approve with nits**, or **Request changes**, then one sentence why.

List findings most severe first, each as:

```
[BLOCKER|MAJOR|MINOR|NIT] path/to/File.scala:123 - short title
Why it matters: concrete scenario (input/state -> wrong behaviour; or "N+1: 1 + <items> queries per /token").
Fix: the smallest change that resolves it.
```

Severity:

- **BLOCKER**: security hole, data loss or corruption, breaking change without approval, a migration
  that breaks rolling upgrade/rollback, a race that breaks a single-use guarantee, an unbounded or
  per-request query on a hot path that will not survive load, CI will fail.
- **MAJOR**: wrong behaviour in a reachable case, missing index for a new query, missing negative
  security test, missing test level without a follow-up, transaction held across slow work, a new
  public surface that duplicates an existing one (section 0).
- **MINOR**: maintainability, naming, a missed reuse, redundant query that is not on a hot path.
- **NIT**: taste; at most three, and only if nothing above them is open.

Add a one-line **Shape** note after the verdict: the alternatives you weighed (section 0) and whether
the PR's shape is kept or which one is cheaper and why.

End with a short **Checked, no issues** list (areas you verified) and a **Not verified** list (what you
could not run or judge from the diff: e2e, loadgen, query plans), so the author knows the review's
limits.

## Do not flag

- Formatting that `scalafmt` decides, or import order.
- Pre-existing problems the PR does not touch (mention separately as a follow-up, not as a finding).
- Generated files (`central/src/main/resources/forms/`, `*/dev/`, `target/`, lockfiles) beyond
  confirming they were not hand-edited.
- Hypothetical scale problems on a path that is provably cold and bounded (admin console CRUD with a
  small cardinality). Say it is cold and move on.
