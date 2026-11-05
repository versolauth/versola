package versola.central.audit

import versola.util.audit.AuditKind
import zio.http.Method

/** What each route central serves means to the audit log (#469), in one place: the hook ([[AuditHook]]) looks a request's
  * route up here and records nothing it cannot find. A route that is missing fails `AuditRoutesSpec`, which walks every
  * route the app serves, so a new endpoint is classified when it is added and never recorded as `unknown`.
  *
  * The kinds of a change are `admin.<resource>.<operation>` (design §4.2): `resource` from the closed list below,
  * `operation` is `create` for a `POST`, `update` for a `PUT` or `PATCH`, `delete` for a `DELETE`, and `action:<last segment>`
  * for the routes that do something else to a resource (`rotate-secret`, `generate`, `reset`, ...). A `POST` that stores
  * nothing (a preview, a rendering) is a read.
  */
object AuditRoutes:

  /** Where a request names its target, when the service did not say so through `AuditContext` (which wins): a query
    * parameter or a field of a JSON body. */
  enum Target:
    /** The first of these parameters the request carries (`DELETE /users/sessions` names the user as `userId` or as `id`). */
    case Query(params: List[String])
    case Body(field: String)
    case Nowhere

  enum Entry:
    /** An administrator's change, recorded whatever its outcome. `revealsSecret`: a success hands a secret back (a new
      * client secret, a private key, a one-time token, a temporary password), which is an `admin.secret.revealed` event of
      * its own on top of the change. */
    case Change(resource: String, operation: String, target: Target, targetType: String, revealsSecret: Boolean)

    /** A read that is an event in itself (`admin.user.viewed`, `admin.audit.queried`), recorded on success. */
    case Read(kind: AuditKind, target: Target, targetType: String)

    /** An administrator's read that nobody records. */
    case Unrecorded

    /** Not an administrator's call: auth's syncs and callbacks, the service surface outside production. Auth records what
      * it asks central to do under its own events. */
    case Internal

  val AdminUserViewed: AuditKind = CentralAudit.AdminUserViewed
  val AdminAuditQueried: AuditKind = CentralAudit.AdminAuditQueried

  def change(resource: String, operation: String, target: Target, targetType: Option[String] = None, revealsSecret: Boolean = false) =
    Entry.Change(
      resource = resource,
      operation = operation,
      target = target,
      targetType = targetType.getOrElse(resource),
      revealsSecret = revealsSecret,
    )

  val create = "create"
  val update = "update"
  val delete = "delete"
  def action(segment: String) = s"action:$segment"

  def query(params: String*) = Target.Query(params.toList)
  val body = Target.Body(_)

  /** A `user*` resource acts on the user a request names: in the query of a `DELETE`, in the body of the rest. */
  def user(resource: String, operation: String, target: Target, revealsSecret: Boolean = false) =
    change(resource = resource, operation = operation, target = target, targetType = Some("user"), revealsSecret = revealsSecret)

  val table: Map[(Method, String), Entry] = Map(
    // tenants
    (Method.GET, "/configuration/tenants") -> Entry.Unrecorded,
    (Method.POST, "/configuration/tenants") -> change("tenant", create, body("id")),
    (Method.PUT, "/configuration/tenants") -> change("tenant", update, body("id")),
    (Method.DELETE, "/configuration/tenants") -> change("tenant", delete, query("tenantId")),
    // permissions
    (Method.GET, "/configuration/permissions") -> Entry.Unrecorded,
    (Method.POST, "/configuration/permissions") -> change("permission", create, body("permission")),
    (Method.PUT, "/configuration/permissions") -> change("permission", update, body("permission")),
    (Method.DELETE, "/configuration/permissions") -> change("permission", delete, query("permission")),
    // resources
    (Method.GET, "/configuration/resources") -> Entry.Unrecorded,
    (Method.GET, "/configuration/resources/registry") -> Entry.Internal,
    (Method.POST, "/configuration/resources") -> change("resource", create, body("resourceId"), revealsSecret = true),
    (Method.PUT, "/configuration/resources") -> change("resource", update, body("resourceId")),
    (Method.DELETE, "/configuration/resources") -> change("resource", delete, query("resourceId")),
    (Method.POST, "/configuration/resources/rotate-secret") ->
      change("resource-secret", action("rotate-secret"), query("resourceId"), Some("resource"), revealsSecret = true),
    (Method.DELETE, "/configuration/resources/previous-secret") -> change("resource-secret", delete, query("resourceId"), Some("resource")),
    // clients
    (Method.GET, "/configuration/clients") -> Entry.Unrecorded,
    (Method.GET, "/configuration/clients/sync") -> Entry.Internal,
    (Method.POST, "/configuration/clients/web") -> change("client", create, body("id"), revealsSecret = true),
    (Method.POST, "/configuration/clients/native") -> change("client", create, body("id"), revealsSecret = true),
    (Method.POST, "/configuration/clients/service") -> change("client", create, body("id"), revealsSecret = true),
    (Method.PUT, "/configuration/clients/web") -> change("client", update, body("clientId")),
    (Method.PUT, "/configuration/clients/native") -> change("client", update, body("clientId")),
    (Method.PUT, "/configuration/clients/service") -> change("client", update, body("clientId")),
    (Method.DELETE, "/configuration/clients") -> change("client", delete, query("clientId")),
    (Method.POST, "/configuration/clients/rotate-secret") ->
      change("client-secret", action("rotate-secret"), query("clientId"), Some("client"), revealsSecret = true),
    (Method.DELETE, "/configuration/clients/previous-secret") -> change("client-secret", delete, query("clientId"), Some("client")),
    (Method.POST, "/configuration/clients/edge-certificate/renew") -> change("client", action("renew"), query("clientId")),
    (Method.POST, "/configuration/clients/est/enrollment-tokens") ->
      change("client-est", action("enrollment-tokens"), query("clientId"), Some("client"), revealsSecret = true),
    (Method.POST, "/configuration/clients/est/extend") -> change("client-est", action("extend"), query("clientId"), Some("client")),
    (Method.POST, "/configuration/clients/est/revoke") -> change("client-est", action("revoke"), query("clientId"), Some("client")),
    (Method.POST, "/est/enroll") -> Entry.Internal,
    (Method.POST, "/est/renew") -> Entry.Internal,
    // presets, scopes, authorization detail types
    (Method.GET, "/configuration/auth-request-presets") -> Entry.Unrecorded,
    (Method.POST, "/configuration/auth-request-presets") -> change("auth-request-preset", create, body("clientId"), Some("client")),
    (Method.GET, "/configuration/scopes") -> Entry.Unrecorded,
    (Method.GET, "/configuration/scopes/sync") -> Entry.Internal,
    (Method.POST, "/configuration/scopes") -> change("scope", create, body("id")),
    (Method.PUT, "/configuration/scopes") -> change("scope", update, body("id")),
    (Method.DELETE, "/configuration/scopes") -> change("scope", delete, query("scopeId")),
    (Method.GET, "/configuration/authorization-detail-types") -> Entry.Unrecorded,
    (Method.GET, "/configuration/authorization-detail-types/sync") -> Entry.Internal,
    (Method.POST, "/configuration/authorization-detail-types") -> change("authorization-detail-type", create, body("type")),
    (Method.PUT, "/configuration/authorization-detail-types") -> change("authorization-detail-type", update, body("type")),
    (Method.DELETE, "/configuration/authorization-detail-types") -> change("authorization-detail-type", delete, query("type")),
    // roles
    (Method.GET, "/configuration/roles") -> Entry.Unrecorded,
    (Method.POST, "/configuration/roles") -> change("role", create, body("id")),
    (Method.PUT, "/configuration/roles") -> change("role", update, body("id")),
    (Method.DELETE, "/configuration/roles") -> change("role", delete, query("roleId")),
    // edges
    (Method.GET, "/configuration/edges") -> Entry.Unrecorded,
    (Method.GET, "/configuration/edges/registry") -> Entry.Internal,
    (Method.GET, "/configuration/edges/policies") -> Entry.Unrecorded,
    (Method.GET, "/configuration/edges/rate-limits/recommended") -> Entry.Unrecorded,
    (Method.GET, "/configuration/edge-groups") -> Entry.Unrecorded,
    (Method.POST, "/configuration/edges") -> change("edge", create, body("id"), revealsSecret = true),
    (Method.DELETE, "/configuration/edges") -> change("edge", delete, query("edgeId")),
    (Method.POST, "/configuration/edges/extend") -> change("edge", action("extend"), query("edgeId")),
    (Method.POST, "/configuration/edges/enrollment-tokens") -> change("edge", action("enrollment-tokens"), query("edgeId"), revealsSecret = true),
    (Method.PUT, "/configuration/edges/dpop") -> change("edge", action("dpop"), query("edgeId")),
    (Method.PATCH, "/configuration/edges/policies") -> change("edge-policy", update, query("edgeId"), Some("edge")),
    (Method.POST, "/configuration/edges/rotate-key") -> change("edge-key", action("rotate-key"), query("edgeId"), Some("edge"), revealsSecret = true),
    (Method.DELETE, "/configuration/edges/old-key") -> change("edge-key", delete, query("edgeId"), Some("edge")),
    (Method.POST, "/configuration/edge-groups") -> change("edge-group", create, body("id")),
    // forms, locales, themes
    (Method.GET, "/configuration/forms") -> Entry.Unrecorded,
    (Method.GET, "/configuration/forms/previews") -> Entry.Unrecorded,
    (Method.GET, "/configuration/forms/sync") -> Entry.Internal,
    (Method.PUT, "/configuration/forms") -> change("form", update, body("id")),
    (Method.PUT, "/configuration/forms/active") -> change("form", action("active"), body("id")),
    (Method.GET, "/configuration/locales") -> Entry.Unrecorded,
    (Method.GET, "/configuration/locales/sync") -> Entry.Internal,
    (Method.PUT, "/configuration/locales") -> change("locale", update, Target.Nowhere),
    (Method.PUT, "/configuration/locales/default") -> change("locale", action("default"), body("code")),
    (Method.GET, "/configuration/themes") -> Entry.Unrecorded,
    (Method.GET, "/configuration/themes/presets") -> Entry.Unrecorded,
    (Method.GET, "/configuration/themes/variables") -> Entry.Unrecorded,
    (Method.GET, "/configuration/themes/sync") -> Entry.Internal,
    // renders a stylesheet from the given tokens; stores nothing
    (Method.POST, "/configuration/themes/css") -> Entry.Unrecorded,
    (Method.POST, "/configuration/themes") -> change("theme", create, body("id")),
    (Method.PUT, "/configuration/themes") -> change("theme", update, body("id")),
    (Method.DELETE, "/configuration/themes") -> change("theme", delete, query("id")),
    // challenges
    (Method.GET, "/configuration/challenges/otp-templates") -> Entry.Unrecorded,
    (Method.GET, "/configuration/challenges/otp-templates/sync") -> Entry.Internal,
    (Method.PUT, "/configuration/challenges/otp-templates") -> change("otp-template", update, body("id")),
    (Method.DELETE, "/configuration/challenges/otp-templates") -> change("otp-template", delete, body("id")),
    (Method.GET, "/configuration/challenges/challenge-settings") -> Entry.Unrecorded,
    (Method.GET, "/configuration/challenges/challenge-settings/sync") -> Entry.Internal,
    (Method.PUT, "/configuration/challenges/challenge-settings") -> change("challenge-settings", update, body("tenantId"), Some("tenant")),
    (Method.PATCH, "/configuration/challenges/challenge-settings") -> change("challenge-settings", update, body("tenantId"), Some("tenant")),
    // deployment-wide settings
    (Method.GET, "/configuration/system-settings") -> Entry.Unrecorded,
    (Method.GET, "/configuration/system-settings/sync") -> Entry.Internal,
    (Method.PUT, "/configuration/system-settings") -> change("system-settings", update, Target.Nowhere),
    (Method.GET, "/configuration/delivery-settings") -> Entry.Unrecorded,
    (Method.GET, "/configuration/delivery-settings/sync") -> Entry.Internal,
    (Method.PUT, "/configuration/delivery-settings") -> change("delivery-settings", update, Target.Nowhere),
    (Method.DELETE, "/configuration/delivery-settings") -> change("delivery-settings", delete, Target.Nowhere),
    (Method.GET, "/configuration/server-metadata") -> Entry.Unrecorded,
    (Method.GET, "/configuration/server-metadata/sync") -> Entry.Internal,
    (Method.POST, "/configuration/server-metadata") -> change("server-metadata", create, Target.Nowhere),
    // keys
    (Method.GET, "/configuration/jwks") -> Entry.Unrecorded,
    (Method.GET, "/configuration/jwks/keys") -> Entry.Unrecorded,
    (Method.GET, "/configuration/jwks/sync") -> Entry.Internal,
    (Method.GET, "/configuration/jwks/signing-keys/sync") -> Entry.Internal,
    (Method.POST, "/configuration/jwks") -> change("jwks", create, body("kid")),
    (Method.POST, "/configuration/jwks/generate") -> change("jwks", action("generate"), Target.Nowhere),
    (Method.PUT, "/configuration/jwks") -> change("jwks", update, body("kid")),
    (Method.DELETE, "/configuration/jwks") -> change("jwks", delete, query("kid")),
    // users: the target user is the `userId` column of the row as well
    (Method.GET, "/users") -> Entry.Read(AdminUserViewed, query("id"), "user"),
    (Method.GET, "/users/roles") -> Entry.Unrecorded,
    (Method.GET, "/users/sessions") -> Entry.Unrecorded,
    (Method.GET, "/users/user-agents") -> Entry.Unrecorded,
    (Method.GET, "/users/refresh-tokens") -> Entry.Unrecorded,
    (Method.GET, "/users/passkeys") -> Entry.Unrecorded,
    (Method.POST, "/users") -> user("user", create, Target.Nowhere),
    (Method.POST, "/users/registrations") -> Entry.Internal,
    (Method.PATCH, "/users") -> user("user", update, body("id")),
    (Method.DELETE, "/users") -> user("user", delete, query("id")),
    (Method.PATCH, "/users/claims") -> user("user-claims", update, body("id")),
    (Method.PATCH, "/users/roles") -> user("user-roles", update, body("userId")),
    (Method.DELETE, "/users/sessions") -> user("user-session", delete, query("userId", "id")),
    (Method.DELETE, "/users/user-agents") -> user("user-user-agent", delete, query("id")),
    (Method.DELETE, "/users/refresh-tokens") -> user("user-refresh-token", delete, query("id")),
    (Method.POST, "/users/limits/reset") -> user("user-limits", action("reset"), body("userId")),
    (Method.PATCH, "/users/passkeys") -> user("user-passkey", update, body("userId")),
    (Method.DELETE, "/users/passkeys") -> user("user-passkey", delete, query("id")),
    // the temporary password is in the answer only where the reset asked to be shown it (never in production)
    (Method.POST, "/users/password/reset") -> user("user-password", action("reset"), body("userId"), revealsSecret = true),
    (Method.POST, "/users/password/set") -> user("user-password", action("set"), body("userId")),
    // antifraud
    (Method.GET, "/antifraud/lists") -> Entry.Unrecorded,
    (Method.GET, "/antifraud/lists/audit") -> Entry.Unrecorded,
    (Method.GET, "/antifraud/global/lists") -> Entry.Unrecorded,
    (Method.GET, "/antifraud/global/lists/audit") -> Entry.Unrecorded,
    (Method.GET, "/antifraud/settings") -> Entry.Unrecorded,
    (Method.GET, "/antifraud/rules") -> Entry.Unrecorded,
    (Method.GET, "/antifraud/status") -> Entry.Unrecorded,
    (Method.POST, "/antifraud/lists") -> change("antifraud-list", create, Target.Nowhere),
    (Method.POST, "/antifraud/lists/preview") -> Entry.Unrecorded,
    (Method.DELETE, "/antifraud/lists") -> change("antifraud-list", delete, body("id")),
    (Method.POST, "/antifraud/global/lists") -> change("antifraud-list", create, Target.Nowhere),
    (Method.POST, "/antifraud/global/lists/preview") -> Entry.Unrecorded,
    (Method.DELETE, "/antifraud/global/lists") -> change("antifraud-list", delete, body("id")),
    (Method.PUT, "/antifraud/settings") -> change("antifraud-settings", update, Target.Nowhere),
    (Method.PATCH, "/antifraud/settings") -> change("antifraud-settings", update, Target.Nowhere),
    (Method.DELETE, "/antifraud/settings") -> change("antifraud-settings", delete, Target.Nowhere),
    (Method.POST, "/antifraud/counters/reset") -> change("antifraud-counters", action("reset"), Target.Nowhere),
    // the audit report of a user: its own permission (`audit:read`), not `users:read`
    (Method.GET, "/users/audit") -> Entry.Read(AdminAuditQueried, query("id"), "user"),
    // the service surface (outside production only): the operator's tooling, not an administrator
    (Method.POST, "/service/users/outbox/flush") -> Entry.Internal,
    (Method.POST, "/service/configuration/sync") -> Entry.Internal,
    (Method.DELETE, "/service/users") -> Entry.Internal,
  )

  def of(method: Method, path: String): Option[Entry] = table.get((method, path))
