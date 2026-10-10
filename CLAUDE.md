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

# OpenAPI Spec Rules

The HTTP surface of `auth`, `central` and `edge` is described by hand-maintained OpenAPI specs:
`auth/open-api/*.yaml`, `central/open-api/central.yaml`, `edge/open-api/edge.yaml`. They are
not generated, so they only stay true if every change that touches the surface updates them
in the same change.

- Update the spec whenever you add, remove or rename a route, path/query/header/cookie
  parameter, request or response field, status code, error code, auth method, or enum value
  of a controller -- and whenever behaviour a spec describes changes (validation, defaults,
  auth requirements, headers).
- A new public endpoint gets a spec entry; a new internal-only endpoint (sync, registry,
  `/service/*`, UI flows) is deliberately left out, but state that in the spec's intro if it
  is not already listed there.
- Read the controller and its request/response case classes, not the old spec, as the source
  of truth for what to write. Keep the existing style of the file you are editing.
- Check that each edited YAML still parses and its `$ref`s resolve before committing.
- Mention the spec change in the PR; if you skipped one, say why.

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
