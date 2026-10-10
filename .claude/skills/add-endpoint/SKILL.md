---
name: add-endpoint
description: Add or change an HTTP endpoint in auth, central or edge, end to end (controller, service, repository, wiring, permissions catalog, OpenAPI, unit and e2e tests, central-ui client). Use when asked to add a route, an admin API, an OAuth endpoint, or to expose something over HTTP.
---

# Add an HTTP endpoint

Work through this in order; each step names real files. Examples to copy, not to invent from:
`auth/.../oauth/revoke/` (public OAuth endpoint) and `central/.../configuration/roles/` (admin API).
Read the example of the same kind first. Rules in `.claude/rules/` still apply (`scala-zio.md`,
`observability.md`, `openapi-specs.md`, `testing.md`, `security.md`, `oauth-oidc-protocols.md`).
Compatibility is the default: do not change an existing request or response shape; add alongside.

## 0. Decide where it lives

- **auth**: OAuth/OIDC endpoints. **central**: configuration/admin API, reached only through edge.
  **edge**: the proxy. For a protocol endpoint, read the standard's page first
  (`oauth-oidc-protocols.md`).
- Public or internal? Internal-only routes (sync, registry, `/service/*`, UI flows) are not in the
  OpenAPI spec, but say so in the spec's intro if not already listed.

## 1. Controller

`object XController extends Controller` with `type Env = ...`, `def routes: Routes[Env, Throwable]`,
and `val fooEndpoint = Method.POST / "path" -> handler { ... }`. Logic stays in the service.

- **auth**: parse the form, authenticate the client, fill `Observability` (`setClientId`, ...). A new
  client-authenticated endpoint needs a case in `AuthenticatedEndpoint` (`ClientAuthentication.scala`).
  Every failure path calls `Observability.setError(code, description)` (`observability.md`).
- **central**: start with `authorizeBasic(request)` (admin) or `authorizeInternal(request)` (`/sync`
  for auth and edge). Body: `request.bodyAs[T]`; domain validation `Left` maps to a 400 JSON body.
  Central has no per-permission check of its own: enforcement happens in edge (step 6).

## 2. Service, repository, models

- Service: trait + `object X { def live: ZLayer[...] }` + `private class Impl`. The `live` input
  list must match the `Impl` constructor positionally.
- Repository trait in the service module; Postgres implementation under
  `*/implementations/postgres/`, using `xa.connectMeasured("name")` (never raw `.connect`; CI and
  `.claude/hooks/check-scala-edit.sh` reject it).
- Request/response DTOs: central keeps them in `central/.../configuration/dto.scala`; auth keeps
  them next to the feature (`model/`). Errors are an enum/ADT per feature with an HTTP status.
- New table or column: use the `add-migration` skill.

## 3. Wire it (a missing route is silent, a missing layer is a compile error)

- **auth** `auth/implementations/postgres/src/main/scala/versola/PostgresOAuthApp.scala`: the
  `Dependencies` type, `routes` (and `mutualTlsRoutes` if it takes a client certificate), and the
  `dependencies` layer chain (order matters: a layer after what it needs). For an mTLS-relevant
  endpoint also update `MtlsRelevantFields` in `ServedMetadata.scala` and `auth/open-api/metadata.yaml`.
- **central** `central/implementations/postgres/src/main/scala/versola/PostgresCentralApp.scala`:
  `Dependencies`, `routes`, the repository chain, the service chain.
- **Cached entity in central** (read from a `ReloadingCache`): the whole sync chain, or auth/edge
  never see changes: a notify trigger migration (see `V1009__notify_triggers.sql`), a case in
  `SyncEvent.scala`, the channel and `parseNotification` in `PostgresCacheSyncRepository`, a case in
  `CacheSyncService.runForeach` and its positional `Impl(_, _, ...)`, plus their specs.

## 4. Admin permission catalog (central endpoints only; the classic omission)

`central/implementations/postgres/src/main/scala/versola/BootstrapService.scala`: add the route to
`centralEndpointCatalog`, then to the right permission in `permissionCatalog` (or a new permission
and the roles in `roleCatalog`). `resourceManagementEndpointIds` is a separate explicit set; check
`utilityClientPermissions` if the loadgen client needs it. Without this the route works with Basic
auth (so e2e passes) but is denied for admins through edge. Bootstrap adds missing endpoints to
existing deployments.

## 5. OpenAPI

Update `auth/open-api/<family>.yaml`, `central/open-api/central.yaml` or `edge/open-api/edge.yaml`
in the same change, from the controller and its DTOs (`openapi-specs.md`). Parse the YAML and check
that `$ref`s resolve.

## 6. Tests (both levels, CLAUDE.md "Test Coverage Rules")

- Unit: `XServiceSpec` (stub the repository), `XControllerSpec` (`UnitSpecBase`, `NoopTracing`,
  `TestEnvConfig`; central uses `TestAdminAuth.basicAuthHeader`), and a repository spec using the
  abstract `XRepositorySpec` + `PostgresXRepositorySpec` pattern. Negative cases for every
  security rule.
- E2E: a spec under `e2e/src/test/scala/versola/e2e/flows/<area>/` (`E2ESpec`; central uses
  `CentralApiSpec`, polling with `eventually` because the cache is eventually consistent). Add a
  helper to `e2e/.../support/OAuthClient.scala` (auth) when the call is reused. `CentralApi` is
  generic and usually needs no change.

## 7. central-ui (central endpoints the console uses)

`central-ui/src/utils/central-api.ts` (typed call and DTO), `central-ui/tests/mocks.ts` (the
Playwright mock must answer the new route), the component, and a spec. See `frontend.md`; show the
screen to the operator before and after a UI change.

## 8. Finish

Run the `pre-pr-check` skill. Say in the PR which test level you could not run and why.
