package versola.loadgen.provision

import versola.loadgen.config.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** In-memory stand-in for the three hops a provision run makes -- auth's token endpoint, edge's
  * proxy and central's configuration API behind it -- with the two behaviours the provisioner's
  * idempotency actually turns on: a duplicate client is a `409`, while a duplicate role,
  * permission or resource is the `500` a unique violation surfaces as.
  *
  * Holds real state rather than replaying canned responses, so that "run `provision` twice"
  * is a test of convergence and not of a script -- the second run reads back what the first
  * one wrote, exactly as it would against a live central.
  */
final class FakeCentral(state: Ref[FakeCentral.State], staleClientListing: Boolean):

  import FakeCentral.*

  def handler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request]: request =>
      request.body.asString.orDie.flatMap(body => respond(request, body))

  def snapshot: UIO[State] = state.get

  /** Pre-creates roles, for the specs that write a client on its own: central validates the
    * roles a registration flow grants while saving the client, so a client-only test would
    * otherwise be rejected the way the provisioner's ordering was.
    */
  def withRoles(roleIds: Set[String]): UIO[FakeCentral] =
    state.update(s => s.copy(roles = s.roles ++ roleIds.map(_ -> Set.empty[String]))).as(this)

  /** Grants a permission directly to a stored client -- something the campaign's blueprint never
    * asks for, and therefore the only way to test that the desired-state update takes away what
    * a previous configuration left behind.
    */
  def grantClientPermissions(clientId: String, permissions: Set[String]): UIO[Unit] =
    state.update: s =>
      s.copy(clients = s.clients.updatedWith(clientId)(_.map(c => c.copy(permissions = c.permissions ++ permissions))))

  /** Puts the listing caches back the way a run that lost a race sees them: the next read of
    * each configuration listing answers as if nothing were there, though every write is
    * committed, and catches up on the read after it. The window in which a create-if-missing
    * check takes the create branch for something that is already there.
    */
  def staleListings: UIO[Unit] = state.update(_.copy(coldPaths = coldListingPaths))

  private def cold(path: String): UIO[Boolean] =
    state.modify(s => (s.coldPaths.contains(path), s.copy(coldPaths = s.coldPaths - path)))

  /** Edge's proxy in miniature: an admin call arrives under `/resources/central`, must carry the
    * bearer token auth issued, and is matched against central's own paths with that prefix
    * removed -- the same rest-of-path forwarding `EdgeService.buildUpstreamRequest` does.
    */
  private def respond(request: Request, body: String): UIO[Response] =
    val rawPath = request.url.path.encode
    val proxied = rawPath.startsWith(proxyPrefix + "/")
    val path = if proxied then rawPath.stripPrefix(proxyPrefix) else rawPath
    val record = state.update(s => s.copy(calls = s.calls :+ Call(request.method, path, body)))
    val bearer = request.header(Header.Authorization).exists {
      case Header.Authorization.Bearer(token) => token.stringValue.startsWith(issuedTokenPrefix)
      case _ => false
    } || (request.rawHeader("Authorization").exists(_.startsWith(s"DPoP $issuedTokenPrefix")) &&
      request.rawHeader("DPoP").nonEmpty)
    if proxied && !bearer then record.as(Response.status(Status.Unauthorized))
    else
      missingMember(request.method, path, body).orElse(extraMember(request.method, path, body)) match
        case Some(refusal) => record.as(refusal)
        case None => dispatch(request, path, body, record)

  /** Central decodes the body before any handler runs, so a payload that leaves out a member
    * its DTO declares mandatory is refused there and never reaches the behaviour these specs
    * describe -- the shape of the bug that kept `provision` from registering a client at all.
    */
  private def missingMember(method: Method, path: String, body: String): Option[Response] =
    requiredMembers.get((method, path)).flatMap: required =>
      val document = Json.decoder.decodeJson(body).toOption.collect { case obj: Json.Obj => obj }
      required.toList.sorted.find(member => !document.exists(present(_, member.split('.').toList))).map: member =>
        val trace = member.split('.').mkString(".", ".", "")
        Response.text(s"Failed to decode JSON: $trace(missing)").status(Status.BadRequest)

  /** Central's per-kind client requests refuse a member they do not declare (`@jsonNoExtraFields`)
    * rather than ignoring it, so a payload naming a setting its kind fixes is refused here too. */
  private def extraMember(method: Method, path: String, body: String): Option[Response] =
    allowedMembers.get((method, path)).flatMap: allowed =>
      Json.decoder.decodeJson(body).toOption.collect { case obj: Json.Obj => obj }
        .flatMap(_.fields.map(_._1).find(!allowed.contains(_)))
        .map(member => Response.text(s"Failed to decode JSON: .$member(invalid extra field)").status(Status.BadRequest))

  /** A member named by its path from the document root, so that a nested DTO's own mandatory
    * members are checked too -- `submissionLimits` being present says nothing about the
    * categories inside it, each of which central requires in its own right.
    */
  private def present(document: Json.Obj, path: List[String]): Boolean =
    path match
      case Nil => true
      case member :: rest =>
        document.get(member).exists:
          case nested: Json.Obj => present(nested, rest)
          case _ => rest.isEmpty

  private def dispatch(request: Request, path: String, body: String, record: UIO[Unit]): UIO[Response] =
    record *> ((request.method, path) match
      // auth's token endpoint. Only `client_credentials` for `resource://central` is modelled,
      // since that is the one grant an admin client ever asks for.
      case (Method.POST, "/token") =>
        val form = Form.fromURLEncoded(body, Charsets.Utf8).toOption.getOrElse(Form.empty)
        val requested = form.get("resource").flatMap(_.stringValue)
        val credentialed = request.header(Header.Authorization).exists {
          case Header.Authorization.Basic(user, _) => user.nonEmpty
          case _ => false
        } || (form.get("client_assertion").flatMap(_.stringValue).nonEmpty && request.rawHeader("DPoP").nonEmpty)
        if !credentialed || requested.contains("resource://central") == false then
          ZIO.succeed(json(Json.Obj("error" -> Json.Str("invalid_target")), Status.BadRequest))
        else
          state.modify: s =>
            (
              json(Json.Obj("access_token" -> Json.Str(s"$issuedTokenPrefix${s.tokensIssued + 1}"))),
              s.copy(tokensIssued = s.tokensIssued + 1),
            )

      // Edge serves a resource it has synced and 404s one it has not, which is what
      // `awaitEdgeConfiguration` reads to tell that its writes have landed. Real edge 404s a
      // probe against a rest-of-path with no matching registered endpoint the same as one
      // against a resource it has not cached at all -- this stands in for both by keying on the
      // resource alone, since the endpoint the provisioner probes is always one it registered.
      case (_, probe) if probe.startsWith("/resources/") && !probe.startsWith(proxyPrefix) =>
        val resourceId = probe.stripPrefix("/resources/").takeWhile(_ != '/')
        state.get.map: s =>
          if s.resources.contains(resourceId) && s.authSyncs > 0 then Response.status(Status.NoContent)
          else Response.status(Status.NotFound)

      case (Method.GET, "/configuration/clients") =>
        // `staleClientListing` reproduces the one race the provisioner cannot read its way out of:
        // the listing is served from a cache a PostgreSQL notification refreshes, so a peer's
        // client can be absent from it and still conflict on create.
        state.get.map: s =>
          val visible = if staleClientListing then Nil else s.clients.toList.sortBy(_._1)
          json(Json.Obj("clients" -> array(visible.map { (clientId, stored) =>
            Json.Obj(
              "id" -> Json.Str(clientId),
              "redirectUris" -> array(stored.redirectUris.toList.sorted.map(Json.Str(_))),
              "scope" -> array(stored.scopes.toList.sorted.map(Json.Str(_))),
              "permissions" -> array(stored.permissions.toList.sorted.map(Json.Str(_))),
              "kind" -> Json.Str(stored.kind),
            )
          })))

      case (Method.POST, kindPath) if clientKinds.contains(kindPath) =>
        val spec = parse(body)
        val clientId = str(spec, "id")
        val create = state.modify: s =>
          if s.clients.contains(clientId) then (Response.status(Status.Conflict), s)
          else
            val secret = if str(spec, "authMethod") == "client_secret" then Some(s"secret-$clientId-0") else None
            val response = json(Json.Obj(secret.map(v => "secret" -> Json.Str(v)).toList*), Status.Created)
            val stored = StoredClient(
              kind = clientKinds(path),
              spec = spec,
              secret = secret,
              redirectUris = strings(spec, "redirectUris").toSet,
              scopes = strings(spec, "allowedScopes").toSet,
              permissions = strings(spec, "permissions").toSet,
            )
            (response, s.copy(clients = s.clients.updated(clientId, stored)))
        create

      case (Method.PUT, kindPath) if clientKinds.contains(kindPath) =>
        val spec = parse(body)
        val clientId = str(spec, "clientId")
        val update = state.modify: s =>
          s.clients.get(clientId) match
            case None => (Response.status(Status.NotFound), s)
            // A client's kind is fixed when it is registered, so central answers an update sent to
            // another kind's endpoint with a conflict rather than applying it.
            case Some(stored) if stored.kind != clientKinds(path) => (Response.status(Status.Conflict), s)
            case Some(stored) =>
              (Response.status(Status.NoContent), s.copy(clients = s.clients.updated(clientId, stored.updated(spec))))
        update

      case (Method.POST, "/configuration/clients/rotate-secret") =>
        val clientId = request.url.queryParams.queryParam("clientId").getOrElse("")
        state.modify: s =>
          s.clients.get(clientId) match
            case None => (Response.status(Status.NotFound), s)
            case Some(stored) =>
              val rotated = s"secret-$clientId-${stored.rotations + 1}"
              (
                json(Json.Obj("secret" -> Json.Str(rotated))),
                s.copy(clients = s.clients.updated(clientId, stored.rotated(rotated))),
              )

      case (Method.GET, "/configuration/resources") =>
        cold(path).zip(state.get).map: (stale, s) =>
          val visible = if stale then Nil else s.resources.toList.sortBy(_._1)
          json(Json.Obj("resources" -> array(visible.map { (resourceId, stored) =>
            Json.Obj(
              "resourceId" -> Json.Str(resourceId),
              "audience" -> array(stored.audience.toList.sorted.map(Json.Str(_))),
              "endpoints" -> array(stored.endpointIds.toList.sortBy(_.toString).map(idObject)),
            )
          })))

      case (Method.POST, "/configuration/resources") =>
        val spec = parse(body)
        val resourceId = str(spec, "resourceId")
        state.modify: s =>
          if s.resources.contains(resourceId) then (uniqueViolation, s)
          else
            val stored = StoredResource(
              spec,
              objects(spec, "endpoints").map(endpointIdOf).toSet,
              audience = strings(spec, "audience").toSet,
            )
            (Response.status(Status.Created), s.copy(resources = s.resources.updated(resourceId, stored)))

      case (Method.PUT, "/configuration/resources") =>
        val spec = parse(body)
        val resourceId = str(spec, "resourceId")
        state.modify: s =>
          s.resources.get(resourceId) match
            case None => (Response.status(Status.NoContent), s)
            case Some(stored) =>
              val deleted = strings(spec, "deleteEndpoints").map(UUID.fromString).toSet
              val created = objects(spec, "createEndpoints").map(endpointIdOf).toSet
              val endpoints = (stored.endpointIds -- deleted -- created) ++ created
              val updated = StoredResource(spec, endpoints, audience = stored.patchedAudience(spec))
              (Response.status(Status.NoContent), s.copy(resources = s.resources.updated(resourceId, updated)))

      case (Method.GET, "/configuration/permissions") =>
        cold(path).zip(state.get).map: (stale, s) =>
          val visible = if stale then Nil else s.permissions.keys.toList.sorted
          json(Json.Obj("permissions" -> array(visible.map: permission =>
            Json.Obj("permission" -> Json.Str(permission)))))

      case (Method.POST, "/configuration/permissions") =>
        val spec = parse(body)
        val permission = str(spec, "permission")
        state.modify: s =>
          if s.permissions.contains(permission) then (uniqueViolation, s)
          else
            val endpointIds = strings(spec, "endpointIds").map(UUID.fromString).toSet
            (Response.status(Status.Created), s.copy(permissions = s.permissions.updated(permission, endpointIds)))

      case (Method.PUT, "/configuration/permissions") =>
        val spec = parse(body)
        val permission = str(spec, "permission")
        state.modify: s =>
          if !s.permissions.contains(permission) then (Response.status(Status.NoContent), s)
          else
            val endpointIds = strings(spec, "endpointIds").map(UUID.fromString).toSet
            (Response.status(Status.NoContent), s.copy(permissions = s.permissions.updated(permission, endpointIds)))

      case (Method.GET, "/configuration/roles") =>
        cold(path).zip(state.get).map: (stale, s) =>
          val visible = if stale then Nil else s.roles.toList.sortBy(_._1)
          json(Json.Obj("roles" -> array(visible.map { (roleId, granted) =>
            Json.Obj("id" -> Json.Str(roleId), "permissions" -> array(granted.toList.sorted.map(Json.Str(_))))
          })))

      case (Method.POST, "/configuration/roles") =>
        val spec = parse(body)
        val roleId = str(spec, "id")
        state.modify: s =>
          if s.roles.contains(roleId) then (uniqueViolation, s)
          else
            (Response.status(Status.Created), s.copy(roles = s.roles.updated(roleId, strings(spec, "permissions").toSet)))

      case (Method.PUT, "/configuration/roles") =>
        val spec = parse(body)
        val roleId = str(spec, "id")
        state.modify: s =>
          s.roles.get(roleId) match
            case None => (Response.status(Status.NoContent), s)
            case Some(granted) =>
              val patch = obj(spec, "permissions")
              val updated = (granted -- strings(patch, "remove").toSet) ++ strings(patch, "add").toSet
              (Response.status(Status.NoContent), s.copy(roles = s.roles.updated(roleId, updated)))

      case (Method.POST, "/configuration/auth-request-presets") =>
        val spec = parse(body)
        state.modify: s =>
          (
            Response.status(Status.NoContent),
            s.copy(presets = s.presets.updated(str(spec, "clientId"), objects(spec, "presets"))),
          )

      // Tenants, as central serves them. A profile is fixed when a tenant is created, which is what
      // the challenge-settings write below holds a request to.
      case (Method.GET, "/configuration/tenants") =>
        state.get.map: s =>
          json(Json.Obj("tenants" -> array(s.tenants.toList.sortBy(_._1).map { (id, tenant) =>
            Json.Obj(
              Chunk[(String, Json)]("id" -> Json.Str(id), "description" -> Json.Str(tenant.description)) ++
                Chunk.fromIterable(tenant.edgeId.map(edge => "edgeId" -> Json.Str(edge))),
            )
          })))

      case (Method.POST, "/configuration/tenants") =>
        val spec = parse(body)
        val id = str(spec, "id")
        val profile = optionalStr(spec, "securityProfile").getOrElse("fapi2")
        state.modify: s =>
          s.tenants.get(id) match
            case Some(existing) if existing.profile != profile =>
              (Response.text("cannot be changed after the tenant is created").status(Status.BadRequest), s)
            case _ =>
              (
                Response.status(Status.Created),
                s.copy(tenants = s.tenants.updated(id, StoredTenant(str(spec, "description"), optionalStr(spec, "edgeId"), profile))),
              )

      case (Method.PUT, "/configuration/tenants") =>
        val spec = parse(body)
        val id = str(spec, "id")
        state.modify: s =>
          (
            Response.status(Status.NoContent),
            s.copy(tenants = s.tenants.updatedWith(id)(_.map(_.copy(edgeId = optionalStr(spec, "edgeId"))))),
          )

      case (Method.GET, "/configuration/challenges/challenge-settings") =>
        val tenant = request.url.queryParams.getAll("tenantId").headOption.getOrElse("")
        state.get.map: s =>
          s.tenants.get(tenant) match
            case Some(found) => json(Json.Obj("settings" -> Json.Obj("securityProfile" -> Json.Str(found.profile))))
            case None => Response.status(Status.NotFound)

      case (Method.PUT, "/configuration/challenges/challenge-settings") =>
        val spec = parse(body)
        state.modify: s =>
          val fixed = s.tenants.get(str(spec, "tenantId")).map(_.profile)
          if optionalStr(spec, "securityProfile").exists(wanted => fixed.exists(_ != wanted)) then
            (Response.text("securityProfile cannot be changed after the tenant is created").status(Status.BadRequest), s)
          else (Response.status(Status.NoContent), s.copy(challengeSettings = Some(spec)))

      case (Method.POST, "/service/users/outbox/flush") =>
        state.modify(s => (Response.status(Status.NoContent), s.copy(outboxFlushes = s.outboxFlushes + 1)))

      case (Method.POST, "/service/configuration/sync") =>
        state.modify(s => (Response.status(Status.NoContent), s.copy(authSyncs = s.authSyncs + 1)))

      case _ => ZIO.succeed(Response.status(Status.NotFound)))

object FakeCentral:

  case class Call(method: Method, path: String, body: String)

  /** The four endpoints where a POST means "create", and a second one for the same id is either a
    * conflict or a unique violation. The other POSTs -- presets, secret rotation, the syncs -- are
    * idempotent by construction.
    */
  val createPaths: List[String] = List(
    "/configuration/clients/web",
    "/configuration/clients/native",
    "/configuration/clients/service",
    "/configuration/resources",
    "/configuration/permissions",
    "/configuration/roles",
  )

  /** The endpoint each kind of client is registered and updated at, and the `ClientKind` central
    * records for it: `native` is the one it calls `device`. */
  val clientKinds: Map[String, String] = Map(
    "/configuration/clients/web" -> "web",
    "/configuration/clients/native" -> "device",
    "/configuration/clients/service" -> "service",
  )

  /** A write to one of the per-kind client endpoints. */
  def isClientWrite(call: Call): Boolean =
    clientKinds.contains(call.path) && (call.method == Method.POST || call.method == Method.PUT)

  /** The members central's DTO for each write declares mandatory -- neither optional nor
    * carrying a default, so zio-json refuses a body without them. A dotted name is a member of
    * a nested DTO, which central requires just as strictly as one at the root.
    *
    * Kept in step with `versola.central.configuration.dto` by hand, as the payloads themselves
    * are: this stand-in is the only thing between a member added to a registration DTO and a
    * `provision` run that fails against a real central.
    */
  private val signInMembers = Set(
    "redirectUris",
    "refreshTokenTtl",
    "theme",
    "authFlow",
    "otpTemplateId",
    "frontChannelLogoutUri",
    "frontChannelLogoutSessionRequired",
    "backChannelLogoutUri",
    "logoUri",
    "policyUri",
    "tosUri",
    "consentFlow",
  )

  /** Every member the per-kind client requests declare, mandatory or not -- mirrors
    * `TypedClientRequests.scala`, by hand like the rest of this stand-in. */
  val allowedMembers: Map[(Method, String), Set[String]] =
    val create = Set(
      "tenantId",
      "id",
      "clientName",
      "allowedScopes",
      "accessTokenTtl",
      "authMethod",
      "mtlsAuth",
      "dpopSigningAlgs",
      "dpopMinRsaKeySize",
    )
    val update = Set("clientId", "clientName", "scope", "accessTokenTtl", "dpopSigningAlgs", "dpopMinRsaKeySize")
    val updateSignIn = signInMembers - "issueEdgeClientCertificate"
    Map(
      (Method.POST, "/configuration/clients/web") -> (create ++ signInMembers ++ Set(
        "issueEdgeClientCertificate",
        "enrollEdgeClientCertificate",
        "dpopBoundAccessTokens",
        "certificateBoundAccessTokens",
        "jwks",
        "generateJwks",
        "requireSignedRequestObject",
        "requirePushedAuthorizationRequests",
        "edgeSigningKey",
        "edgeClientCertificate",
        "estEnrollment",
      )),
      (Method.POST, "/configuration/clients/native") -> (create ++ signInMembers ++ Set(
        "issueEdgeClientCertificate",
        "enrollEdgeClientCertificate",
        "dpopBoundAccessTokens",
        "edgeClientCertificate",
      )),
      (
        Method.POST,
        "/configuration/clients/service",
      ) -> (create ++ Set("permissions", "dpopBoundAccessTokens", "certificateBoundAccessTokens", "jwks", "generateJwks", "estEnrollment")),
      (Method.PUT, "/configuration/clients/web") -> (update ++ updateSignIn ++ Set(
        "redirectUris",
        "dpopBoundAccessTokens",
        "mtlsAuth",
        "certificateBoundAccessTokens",
        "jwks",
        "requireSignedRequestObject",
        "requirePushedAuthorizationRequests",
        "edgeSigningKey",
        "edgeClientCertificate",
      )),
      (
        Method.PUT,
        "/configuration/clients/native",
      ) -> (update ++ updateSignIn ++ Set("redirectUris", "dpopBoundAccessTokens", "edgeClientCertificate")),
      (
        Method.PUT,
        "/configuration/clients/service",
      ) -> (update ++ Set("permissions", "dpopBoundAccessTokens", "mtlsAuth", "certificateBoundAccessTokens", "jwks")),
    )

  val requiredMembers: Map[(Method, String), Set[String]] = Map(
    (Method.POST, "/configuration/clients/web") -> Set(
      "tenantId",
      "id",
      "clientName",
      "redirectUris",
      "allowedScopes",
      "accessTokenTtl",
      "authMethod",
      "issueEdgeClientCertificate",
      "enrollEdgeClientCertificate",
      "theme",
      "otpTemplateId",
      "frontChannelLogoutSessionRequired",
      "dpopBoundAccessTokens",
      "dpopSigningAlgs",
      "certificateBoundAccessTokens",
      "requireSignedRequestObject",
      "requirePushedAuthorizationRequests",
      "estEnrollment",
    ),
    (Method.POST, "/configuration/clients/native") -> Set(
      "tenantId",
      "id",
      "clientName",
      "redirectUris",
      "allowedScopes",
      "accessTokenTtl",
      "authMethod",
      "issueEdgeClientCertificate",
      "enrollEdgeClientCertificate",
      "theme",
      "otpTemplateId",
      "frontChannelLogoutSessionRequired",
      "dpopSigningAlgs",
    ),
    (Method.POST, "/configuration/clients/service") -> Set(
      "tenantId",
      "id",
      "clientName",
      "allowedScopes",
      "permissions",
      "accessTokenTtl",
      "dpopBoundAccessTokens",
      "dpopSigningAlgs",
      "authMethod",
      "certificateBoundAccessTokens",
      "estEnrollment",
    ),
    (Method.PUT, "/configuration/clients/web") -> Set("clientId", "redirectUris", "scope"),
    (Method.PUT, "/configuration/clients/native") -> Set("clientId", "redirectUris", "scope"),
    (Method.PUT, "/configuration/clients/service") -> Set("clientId", "scope", "permissions"),
    (Method.POST, "/configuration/resources") -> Set(
      "tenantId",
      "resourceId",
      "resource",
      "audience",
      "endpoints",
      "internal",
    ),
    (Method.PUT, "/configuration/resources") -> Set(
      "resourceId",
      "audience",
      "audience.add",
      "audience.remove",
      "deleteEndpoints",
      "createEndpoints",
    ),
    (Method.POST, "/configuration/permissions") -> Set("tenantId", "permission", "description", "endpointIds"),
    (Method.PUT, "/configuration/permissions") -> Set("tenantId", "permission", "description"),
    (Method.POST, "/configuration/roles") -> Set("tenantId", "id", "description", "permissions"),
    (Method.PUT, "/configuration/roles") -> Set("tenantId", "id", "description", "permissions"),
    (Method.POST, "/configuration/auth-request-presets") -> Set("clientId", "presets"),
    (Method.PUT, "/configuration/challenges/challenge-settings") -> Set(
      "tenantId",
      "phoneRegions",
      "emailRegistration",
      "emailRegistration.mode",
      "emailRegistration.domains",
      "emailRegistration.blockSubaddress",
      "defaultRoles",
      "submissionLimits",
      "submissionLimits.otpRequest",
      "submissionLimits.otpSubmit",
      "submissionLimits.passwordSubmit",
      "submissionLimits.passkeyAssertion",
      "submissionLimits.banDurationSeconds",
      "otpLength",
      "otpResendAfter",
      "passkeySettings",
      "passkeySettings.rpId",
      "passkeySettings.rpName",
      "passkeySettings.origins",
      "passkeySettings.userVerification",
      "ipHeader",
    ),
  )

  case class StoredClient(
      kind: String,
      spec: Json.Obj,
      secret: Option[String],
      redirectUris: Set[String] = Set.empty,
      scopes: Set[String] = Set.empty,
      permissions: Set[String] = Set.empty,
      rotations: Int = 0,
  ):
    /** Applies the update's patch sets the way central's repository does, removals before
      * additions, so a client whose configuration was narrowed really does lose what the
      * update takes away.
      */
    def updated(spec: Json.Obj): StoredClient =
      copy(
        spec = spec,
        redirectUris = patched(redirectUris, obj(spec, "redirectUris")),
        scopes = patched(scopes, obj(spec, "scope")),
        // Only a service client has permissions: a web or native update carries no such member.
        permissions = spec.get("permissions").collect { case patch: Json.Obj => patched(permissions, patch) }.getOrElse(permissions),
      )

    def rotated(secret: String): StoredClient = copy(secret = Some(secret), rotations = rotations + 1)

    private def patched(current: Set[String], patch: Json.Obj): Set[String] =
      current -- strings(patch, "remove") ++ strings(patch, "add")

  case class StoredTenant(description: String, edgeId: Option[String], profile: String)

  case class StoredResource(spec: Json.Obj, endpointIds: Set[UUID], audience: Set[String] = Set.empty):
    /** Applies the audience patch the way central's `PatchAudience.patch` does: remove before
      * add, so a client moved from one resource's audience to another in the same run is not
      * dropped by whichever write central happens to apply first.
      */
    def patchedAudience(spec: Json.Obj): Set[String] =
      audience -- strings(obj(spec, "audience"), "remove") ++ strings(obj(spec, "audience"), "add")

  /** The listings [[FakeCentral.staleListings]] serves cold. Clients are not among them: their
    * listing has its own flag, because the provisioner's 409 fallback needs it stale throughout.
    */
  val coldListingPaths: Set[String] =
    Set("/configuration/resources", "/configuration/permissions", "/configuration/roles")

  case class State(
      clients: Map[String, StoredClient],
      resources: Map[String, StoredResource],
      permissions: Map[String, Set[UUID]],
      roles: Map[String, Set[String]],
      presets: Map[String, List[Json.Obj]],
      challengeSettings: Option[Json.Obj],
      tenants: Map[String, StoredTenant],
      authSyncs: Int,
      tokensIssued: Int,
      outboxFlushes: Int,
      coldPaths: Set[String],
      calls: Chunk[Call],
  ):
    def callsTo(method: Method, path: String): Chunk[Call] =
      calls.filter(call => call.method == method && call.path == path)

    /** Registrations (`POST`) or updates (`PUT`) of a client, whichever kind's endpoint took them. */
    def clientCalls(method: Method): Chunk[Call] =
      calls.filter(call => call.method == method && clientKinds.contains(call.path))

  val empty: State = State(
    clients = Map.empty,
    resources = Map.empty,
    permissions = Map.empty,
    roles = Map.empty,
    presets = Map.empty,
    challengeSettings = None,
    // Every deployment starts with `default`, on FAPI 2.0, bound to its edge.
    tenants = Map("default" -> StoredTenant("Default tenant", Some("edge-1"), "fapi2")),
    authSyncs = 0,
    tokensIssued = 0,
    outboxFlushes = 0,
    coldPaths = Set.empty,
    calls = Chunk.empty,
  )

  def make(staleClientListing: Boolean = false): UIO[FakeCentral] =
    Ref.make(empty).map(FakeCentral(_, staleClientListing))

  /** A unique-key violation as central reports it: an unhandled repository failure, not a 409.
    * This is why the provisioner reads before it writes.
    */
  /** Where edge publishes central's admin API: `BootstrapService` seeds it as the internal
    * resource `central`, so the proxy's path is this, then central's own. */
  private val proxyPrefix: String = "/resources/central"

  private val issuedTokenPrefix: String = "provisioner-token-"

  private val uniqueViolation: Response =
    Response.text("ERROR: duplicate key value violates unique constraint").status(Status.InternalServerError)

  private def json(body: Json.Obj, status: Status = Status.Ok): Response =
    Response.json(body.toJson).status(status)

  private def array(values: List[Json]): Json.Arr = Json.Arr(Chunk.fromIterable(values))

  private def idObject(id: String): Json.Obj = Json.Obj("id" -> Json.Str(id))

  private def idObject(id: UUID): Json.Obj = idObject(id.toString)

  private def endpointIdOf(endpoint: Json.Obj): UUID = UUID.fromString(str(endpoint, "id"))

  def parse(body: String): Json.Obj =
    Json.decoder.decodeJson(body) match
      case Right(obj: Json.Obj) => obj
      case other => throw RuntimeException(s"not a JSON object: $other")

  def field(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.collectFirst { case (key, value) if key == name => value }

  def str(obj: Json.Obj, name: String): String =
    field(obj, name).collect { case Json.Str(value) => value }
      .getOrElse(throw RuntimeException(s"missing string '$name' in $obj"))

  def optionalStr(obj: Json.Obj, name: String): Option[String] =
    field(obj, name).collect { case Json.Str(value) => value }

  def bool(obj: Json.Obj, name: String): Option[Boolean] =
    field(obj, name).collect { case Json.Bool(value) => value }

  def num(obj: Json.Obj, name: String): Option[BigDecimal] =
    field(obj, name).collect { case Json.Num(value) => BigDecimal(value) }

  def obj(parent: Json.Obj, name: String): Json.Obj =
    field(parent, name).collect { case value: Json.Obj => value }
      .getOrElse(throw RuntimeException(s"missing object '$name' in $parent"))

  def strings(parent: Json.Obj, name: String): List[String] =
    field(parent, name).collect { case Json.Arr(values) => values.collect { case Json.Str(v) => v }.toList }
      .getOrElse(Nil)

  def objects(parent: Json.Obj, name: String): List[Json.Obj] =
    field(parent, name).collect { case Json.Arr(values) => values.collect { case v: Json.Obj => v }.toList }
      .getOrElse(Nil)

/** The deployment-specific half of a campaign's provisioning input, shared by the specs below. */
object ProvisionFixtures:

  val targets: TargetsConfig = TargetsConfig(
    authUrl = "http://auth:8080",
    edgeUrl = "http://edge:8095",
    mockUrl = "http://mockapi:8100",
    origin = "https://bank.example.test",
  )

  val provision: ProvisionConfig = ProvisionConfig(
    tenantId = "loadgen-standard",
    provisionerClientId = "utils",
    provisionerSecret = Some(Config.Secret("provisioner-secret")),
    mobileRedirectUri = "https://app.versola.test/callback",
    resources = ProvisionResourcesConfig(
      coreUri = "http://mockapi-core:8100",
      payUri = "http://mockapi-pay:8100",
      notifyUri = "http://mockapi-notify:8100",
    ),
    preset = ProvisionPresetConfig(
      id = "web-otp",
      cookieDomain = Some("bank.example.test"),
      cookiePath = Some("/"),
      postLogoutRedirectUri = Some("https://bank.example.test/goodbye"),
    ),
    passkey = ProvisionPasskeyConfig(rpId = "bank.example.test", rpName = "Versola Bank", userVerification = "preferred"),
    paymentAmountThreshold = 1000000L,
  )

  val flows: CampaignFlows = CampaignFlows(
    phoneOtpAuthFlow = Json.Obj("primary" -> Json.Str("phone-otp")),
    phoneOtpPasswordAuthFlow = Json.Obj("primary" -> Json.Str("phone-otp-password")),
    phonePasskeyAuthFlow = Json.Obj("primary" -> Json.Str("phone-passkey")),
  )

  val blueprint: CampaignBlueprint = CampaignBlueprint(targets, provision, flows)
