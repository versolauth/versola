---
paths: ["**/open-api/*.yaml", "**/*Controller.scala"]
---

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
