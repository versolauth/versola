package versola.loadgen.provision

import versola.loadgen.config.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** In-memory stand-in for central's configuration API and edge's service API, with the two
  * behaviours the provisioner's idempotency actually turns on: a duplicate client is a `409`,
  * while a duplicate role, permission or resource is the `500` a unique violation surfaces as.
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

  private def respond(request: Request, body: String): UIO[Response] =
    val path = request.url.path.encode
    val record = state.update(s => s.copy(calls = s.calls :+ Call(request.method, path, body)))
    val fromEdge = request.header(Header.Authorization).exists {
      case Header.Authorization.Basic(user, _) => user == "edge"
      case _ => false
    }
    record *> ((request.method, path) match
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
            )
          })))

      case (Method.POST, "/configuration/clients") =>
        val spec = parse(body)
        val clientId = str(spec, "id")
        val create = state.modify: s =>
          if s.clients.contains(clientId) then (Response.status(Status.Conflict), s)
          else
            val secret = if str(spec, "clientType") == "web" then Some(s"secret-$clientId-0") else None
            val response = json(Json.Obj(secret.map(v => "secret" -> Json.Str(v)).toList*), Status.Created)
            val stored = StoredClient(
              spec = spec,
              secret = secret,
              redirectUris = strings(spec, "redirectUris").toSet,
              scopes = strings(spec, "allowedScopes").toSet,
              permissions = strings(spec, "permissions").toSet,
            )
            (response, s.copy(clients = s.clients.updated(clientId, stored)))
        unknownRole(spec).someOrElseZIO(create)

      case (Method.PUT, "/configuration/clients") =>
        val spec = parse(body)
        val clientId = str(spec, "clientId")
        val update = state.modify: s =>
          s.clients.get(clientId) match
            case None => (Response.status(Status.NoContent), s)
            case Some(stored) =>
              (Response.status(Status.NoContent), s.copy(clients = s.clients.updated(clientId, stored.updated(spec))))
        unknownRole(spec).someOrElseZIO(update)

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
              "endpoints" -> array(stored.endpointIds.toList.sortBy(_.toString).map(idObject)),
            )
          })))

      case (Method.POST, "/configuration/resources") =>
        val spec = parse(body)
        val resourceId = str(spec, "resourceId")
        state.modify: s =>
          if s.resources.contains(resourceId) then (uniqueViolation, s)
          else
            val stored = StoredResource(spec, objects(spec, "endpoints").map(endpointIdOf).toSet)
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
              (
                Response.status(Status.NoContent),
                s.copy(resources = s.resources.updated(resourceId, StoredResource(spec, endpoints))),
              )

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

      case (Method.PUT, "/configuration/challenges/challenge-settings") =>
        state.modify(s => (Response.status(Status.NoContent), s.copy(challengeSettings = Some(parse(body)))))

      case (Method.POST, "/service/users/outbox/flush") =>
        state.modify(s => (Response.status(Status.NoContent), s.copy(outboxFlushes = s.outboxFlushes + 1)))

      // Central and edge expose this on the same path, so which one was called is told apart by
      // the credential that arrived rather than by the URL -- the test client routes on path only.
      case (Method.POST, "/service/configuration/sync") =>
        state.modify: s =>
          if fromEdge then (Response.status(Status.NoContent), s.copy(edgeSyncs = s.edgeSyncs + 1))
          else (Response.status(Status.NoContent), s.copy(authSyncs = s.authSyncs + 1))

      case _ => ZIO.succeed(Response.status(Status.NotFound)))

  /** Central validates the roles a client's registration flow grants while saving the client and
    * answers a `400` for one that does not exist yet -- the reason roles are provisioned before
    * any client that names them.
    */
  private def unknownRole(spec: Json.Obj): UIO[Option[Response]] =
    val granted = field(spec, "registrationFlow").collect { case flow: Json.Obj => strings(flow, "roleIds") }
      .getOrElse(Nil)
    state.get.map: s =>
      granted.find(!s.roles.contains(_)).map: roleId =>
        Response.text(s"Invalid registration configuration: role '$roleId' does not exist")
          .status(Status.BadRequest)

object FakeCentral:

  case class Call(method: Method, path: String, body: String)

  /** The four endpoints where a POST means "create", and a second one for the same id is either a
    * conflict or a unique violation. The other POSTs -- presets, secret rotation, the syncs -- are
    * idempotent by construction.
    */
  val createPaths: List[String] = List(
    "/configuration/clients",
    "/configuration/resources",
    "/configuration/permissions",
    "/configuration/roles",
  )

  case class StoredClient(
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
        permissions = patched(permissions, obj(spec, "permissions")),
      )

    def rotated(secret: String): StoredClient = copy(secret = Some(secret), rotations = rotations + 1)

    private def patched(current: Set[String], patch: Json.Obj): Set[String] =
      current -- strings(patch, "remove") ++ strings(patch, "add")

  case class StoredResource(spec: Json.Obj, endpointIds: Set[UUID])

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
      authSyncs: Int,
      edgeSyncs: Int,
      outboxFlushes: Int,
      coldPaths: Set[String],
      calls: Chunk[Call],
  ):
    def callsTo(method: Method, path: String): Chunk[Call] =
      calls.filter(call => call.method == method && call.path == path)

  val empty: State = State(
    clients = Map.empty,
    resources = Map.empty,
    permissions = Map.empty,
    roles = Map.empty,
    presets = Map.empty,
    challengeSettings = None,
    authSyncs = 0,
    edgeSyncs = 0,
    outboxFlushes = 0,
    coldPaths = Set.empty,
    calls = Chunk.empty,
  )

  def make(staleClientListing: Boolean = false): UIO[FakeCentral] =
    Ref.make(empty).map(FakeCentral(_, staleClientListing))

  /** A unique-key violation as central reports it: an unhandled repository failure, not a 409.
    * This is why the provisioner reads before it writes.
    */
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
    centralUrl = "http://central:8090",
    mockUrl = "http://mockapi:8100",
    origin = "https://bank.example.test",
  )

  val provision: ProvisionConfig = ProvisionConfig(
    tenantId = "default",
    centralSecret = Config.Secret("central-secret"),
    edgeSecret = Config.Secret("edge-secret"),
    mobileRedirectUri = "versola://callback",
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
    // Carries `roleIds` because the real document does: central validates the roles a
    // registration flow grants while saving the client that names it.
    registrationFlow = Json.Obj(
      "credential" -> Json.Str("phone"),
      "roleIds" -> Json.Arr(Json.Str(CampaignBlueprint.retailUserRoleId)),
    ),
  )

  val blueprint: CampaignBlueprint = CampaignBlueprint(targets, provision, flows)
