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
