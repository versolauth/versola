package versola.loadgen.protocol

import versola.loadgen.config.{ProvisionConfig, TargetsConfig}
import zio.*
import zio.http.*
import zio.http.Header.Authorization
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** [[AdminClient]] over central's and edge's HTTP admin APIs, authenticating with HTTP Basic the
  * same way e2e's `OAuthClient` does -- the central resource secret for central's configuration
  * API, the edge internal secret for edge's service API.
  *
  * Each `upsert` reads the current state first and then creates or updates, rather than creating
  * and treating the failure as "already there": only `/configuration/clients` reports a duplicate
  * as a `409`, while roles, permissions and resources surface theirs as a unique-violation `500`,
  * which is indistinguishable from central actually being broken. Every listing is served from a
  * cache a PostgreSQL notification refreshes, so a stale read can still lose the race -- hence
  * the `409` fallback on clients, and [[convergeAfterFailedCreate]] on the rest.
  *
  * Not a hot path: this runs once per campaign, so unlike the driver-facing clients (§3.3) it
  * parses whole bodies and builds request objects per call.
  */
final class HttpAdminClient(
    client: Client,
    centralUrl: URL,
    edgeUrl: URL,
    centralSecret: String,
    edgeSecret: String,
    tenantId: String,
) extends AdminClient:

  import HttpAdminClient.*

  private val centralAuthorization = Authorization.Basic("central", centralSecret)
  private val edgeAuthorization = Authorization.Basic("edge", edgeSecret)

  override def registerClient(spec: ClientSpec): Task[ClientCreds] =
    for
      existing <- listClients
      creds <- existing.get(spec.clientId) match
        case Some(current) => adoptClient(spec, current)
        case None =>
          createClient(spec).flatMap:
            case Some(secret) => ZIO.succeed(ClientCreds(spec.clientId, secret))
            // The listing that said the client was absent was stale, so the state the blueprint
            // is diffed against has to be read again rather than assumed empty.
            case None =>
              listClients.flatMap(fresh => adoptClient(spec, fresh.getOrElse(spec.clientId, ClientState.empty)))
    yield creds

  /** Brings a client that already exists up to the blueprint and answers usable credentials.
    *
    * A confidential client's secret is readable exactly once, at registration, so the only way to
    * hand the campaign one for a client a previous run created is to rotate. Central keeps the
    * previous secret alive alongside the new one, so nothing authenticating with the old value
    * breaks at the moment of the rotation, and the rotation itself has no in-progress guard --
    * which is what makes a third and fourth `provision` run work the same as the second.
    */
  private def adoptClient(spec: ClientSpec, current: ClientState): Task[ClientCreds] =
    updateClient(spec, current) *>
      (if spec.publicClient then ZIO.none else rotateClientSecret(spec.clientId).asSome)
        .map(ClientCreds(spec.clientId, _))

  private def createClient(spec: ClientSpec): Task[Option[Option[String]]] =
    val body = CreateClientBody(
      tenantId = tenantId,
      id = spec.clientId,
      clientName = Map(englishTag -> spec.clientName),
      redirectUris = spec.redirectUris,
      allowedScopes = spec.allowedScopes,
      permissions = Set.empty,
      accessTokenTtl = spec.accessTokenTtlSeconds,
      refreshTokenTtl = spec.refreshTokenTtlSeconds,
      theme = defaultTheme,
      authFlow = spec.authFlow,
      registrationFlow = spec.registrationFlow,
      otpTemplateId = defaultOtpTemplateId,
      frontChannelLogoutSessionRequired = false,
      backChannelLogoutUri = spec.backChannelLogoutUri,
      clientType = if spec.publicClient then nativeClientType else webClientType,
    )
    send(Method.POST, central("configuration", "clients"), Some(body.toJson)).flatMap: response =>
      if response.status == Status.Conflict then ZIO.none
      else
        expectSuccess("registerClient", response)
          *> decode[CreateClientResponseBody]("registerClient", response).map(raw => Some(raw.secret))

  /** The multi-value fields are patched rather than replaced, so the removal half has to be
    * computed from what central currently holds: a redirect URI, scope or client permission the
    * blueprint narrowed away would otherwise stay active on the target, leaving an obsolete
    * OAuth redirect target or privilege behind every re-run.
    */
  private def updateClient(spec: ClientSpec, current: ClientState): Task[Unit] =
    val body = UpdateClientBody(
      clientId = spec.clientId,
      clientName = Map(englishTag -> spec.clientName),
      redirectUris = PatchSet(add = spec.redirectUris, remove = current.redirectUris -- spec.redirectUris),
      scope = PatchSet(add = spec.allowedScopes, remove = current.scopes -- spec.allowedScopes),
      // The campaign grants its permissions through roles, so a client that carries one directly
      // carries it from a configuration nothing names any more.
      permissions = PatchSet(add = Set.empty, remove = current.permissions),
      accessTokenTtl = Some(spec.accessTokenTtlSeconds.toLong),
      refreshTokenTtl = spec.refreshTokenTtlSeconds.map(_.toLong),
      theme = Some(defaultTheme),
      otpTemplateId = Some(defaultOtpTemplateId),
      authFlow = spec.authFlow,
      // A patchable field is cleared by an explicit `null` and left alone by omission, so the
      // blueprint's `None` has to be written rather than dropped -- a client that carried a
      // registration flow or a logout URI the campaign no longer wants would otherwise keep it.
      registrationFlow = spec.registrationFlow.getOrElse(Json.Null),
      backChannelLogoutUri = spec.backChannelLogoutUri.fold(Json.Null)(Json.Str(_)),
    )
    send(Method.PUT, central("configuration", "clients"), Some(body.toJson))
      .flatMap(expectSuccess("updateClient", _))

  private def rotateClientSecret(clientId: String): Task[String] =
    val url = central("configuration", "clients", "rotate-secret").addQueryParam("clientId", clientId)
    send(Method.POST, url, None).flatMap: response =>
      expectSuccess("rotateClientSecret", response)
        *> decode[RotateSecretResponseBody]("rotateClientSecret", response).map(_.secret)

  private def listClients: Task[Map[String, ClientState]] =
    val url = central("configuration", "clients").addQueryParam("tenantId", tenantId)
    send(Method.GET, url, None).flatMap: response =>
      expectSuccess("listClients", response)
        *> decode[ClientListBody]("listClients", response).map(_.clients.map { entry =>
          entry.id -> ClientState(entry.redirectUris, entry.scope, entry.permissions)
        }.toMap)

  override def registerResource(spec: ResourceSpec): Task[Unit] =
    listResources.flatMap: existing =>
      existing.get(spec.resourceId) match
        case None => createResource(spec)
        case Some(endpointIds) => updateResource(spec, endpointIds)

  private def createResource(spec: ResourceSpec): Task[Unit] =
    val body = CreateResourceBody(
      tenantId = tenantId,
      resourceId = spec.resourceId,
      resource = spec.resourceUri,
      audience = spec.audience,
      endpoints = spec.endpoints.map(endpointBody),
      // Public rather than internal: an internal resource is proxied with edge's own Basic
      // credentials and never sees the caller's token, which would take the access token off the
      // one hop the campaign is meant to measure end to end.
      internal = false,
    )
    send(Method.POST, central("configuration", "resources"), Some(body.toJson)).flatMap: response =>
      convergeAfterFailedCreate("registerResource", response, listResources.map(_.get(spec.resourceId))): endpointIds =>
        updateResource(spec, endpointIds)

  /** Endpoints are replaced by id, and central's update deletes every id it is about to create
    * before creating it, so sending the full desired set is a single atomic desired-state apply:
    * an endpoint whose ACR or CEL rule changed is rewritten, and one the blueprint dropped is
    * deleted rather than left granting access nothing names any more.
    */
  private def updateResource(spec: ResourceSpec, existingEndpointIds: Set[UUID]): Task[Unit] =
    val desired = spec.endpoints.map(_.id).toSet
    val body = UpdateResourceBody(
      resourceId = spec.resourceId,
      resource = Some(spec.resourceUri),
      audience = Some(spec.audience),
      deleteEndpoints = existingEndpointIds -- desired,
      createEndpoints = spec.endpoints.map(endpointBody),
    )
    send(Method.PUT, central("configuration", "resources"), Some(body.toJson))
      .flatMap(expectSuccess("updateResource", _))

  private def listResources: Task[Map[String, Set[UUID]]] =
    val url = central("configuration", "resources").addQueryParam("tenantId", tenantId)
    send(Method.GET, url, None).flatMap: response =>
      expectSuccess("listResources", response)
        *> decode[ResourceListBody]("listResources", response)
          .map(_.resources.map(entry => entry.resourceId -> entry.endpoints.map(_.id).toSet).toMap)

  override def upsertPermissions(specs: List[PermissionSpec]): Task[Unit] =
    listPermissions.flatMap: existing =>
      ZIO.foreachDiscard(specs): spec =>
        if existing.contains(spec.permission) then updatePermission(spec)
        else createPermission(spec)

  private def createPermission(spec: PermissionSpec): Task[Unit] =
    val body = CreatePermissionBody(
      tenantId = tenantId,
      permission = spec.permission,
      description = Map(englishTag -> spec.description),
      endpointIds = spec.endpointIds,
    )
    send(Method.POST, central("configuration", "permissions"), Some(body.toJson)).flatMap: response =>
      val present = listPermissions.map(existing => Option.when(existing.contains(spec.permission))(()))
      convergeAfterFailedCreate("upsertPermissions", response, present)(_ => updatePermission(spec))

  private def updatePermission(spec: PermissionSpec): Task[Unit] =
    val body = UpdatePermissionBody(
      tenantId = tenantId,
      permission = spec.permission,
      description = PatchText(add = Map(englishTag -> spec.description), delete = Set.empty),
      endpointIds = Some(spec.endpointIds),
    )
    send(Method.PUT, central("configuration", "permissions"), Some(body.toJson))
      .flatMap(expectSuccess("upsertPermissions", _))

  private def listPermissions: Task[Set[String]] =
    val url = central("configuration", "permissions").addQueryParam("tenantId", tenantId)
    send(Method.GET, url, None).flatMap: response =>
      expectSuccess("listPermissions", response)
        *> decode[PermissionListBody]("listPermissions", response).map(_.permissions.map(_.permission).toSet)

  override def upsertRoles(specs: List[RoleSpec]): Task[Unit] =
    listRoles.flatMap: existing =>
      ZIO.foreachDiscard(specs): spec =>
        existing.get(spec.roleId) match
          case Some(granted) => updateRole(spec, granted)
          case None => createRole(spec)

  private def createRole(spec: RoleSpec): Task[Unit] =
    val body = CreateRoleBody(
      tenantId = tenantId,
      id = spec.roleId,
      description = Map(englishTag -> spec.description),
      permissions = spec.permissions,
    )
    send(Method.POST, central("configuration", "roles"), Some(body.toJson)).flatMap: response =>
      convergeAfterFailedCreate("upsertRoles", response, listRoles.map(_.get(spec.roleId))): granted =>
        updateRole(spec, granted)

  /** A role's permissions are patched, not replaced, so the revocation half has to be computed
    * here -- a permission the blueprint moved from `retail-user` to `retail-basic` would
    * otherwise stay granted on both.
    */
  private def updateRole(spec: RoleSpec, granted: Set[String]): Task[Unit] =
    val body = UpdateRoleBody(
      tenantId = tenantId,
      id = spec.roleId,
      description = PatchText(add = Map(englishTag -> spec.description), delete = Set.empty),
      permissions = PatchSet(add = spec.permissions -- granted, remove = granted -- spec.permissions),
    )
    send(Method.PUT, central("configuration", "roles"), Some(body.toJson))
      .flatMap(expectSuccess("upsertRoles", _))

  private def listRoles: Task[Map[String, Set[String]]] =
    val url = central("configuration", "roles").addQueryParam("tenantId", tenantId)
    send(Method.GET, url, None).flatMap: response =>
      expectSuccess("listRoles", response)
        *> decode[RoleListBody]("listRoles", response)
          .map(_.roles.map(entry => entry.id -> entry.permissions).toMap)

  /** Central stores a client's presets as one set, so this call is idempotent by construction --
    * no read first, and a re-run cannot accumulate duplicates.
    */
  override def upsertAuthRequestPresets(spec: AuthRequestPresetsSpec): Task[Unit] =
    val body = SavePresetsBody(
      clientId = spec.clientId,
      presets = spec.presets.map: preset =>
        PresetBody(
          id = preset.presetId,
          description = preset.description,
          redirectUri = preset.redirectUri,
          postLoginRedirectUri = preset.postLoginRedirectUri,
          postLogoutRedirectUri = preset.postLogoutRedirectUri,
          scope = preset.scope,
          responseType = preset.responseType,
          customParameters = Map.empty,
          cookieDomain = preset.cookieDomain,
          cookiePath = preset.cookiePath,
        ),
    )
    send(Method.POST, central("configuration", "auth-request-presets"), Some(body.toJson))
      .flatMap(expectSuccess("upsertAuthRequestPresets", _))

  /** Central's challenge-settings write is a full replace of the tenant's document, so this sets
    * every field the campaign depends on rather than only the ACR vocabulary.
    */
  override def upsertChallengeSettings(spec: ChallengeSettingsSpec): Task[Unit] =
    val body = UpsertChallengeSettingsBody(
      tenantId = tenantId,
      allowedPrefixes = spec.allowedPrefixes,
      submissionLimits = SubmissionLimitsBody(
        otpRequest = Nil,
        otpSubmit = Nil,
        passwordSubmit = Nil,
        passkeyAssertion = Nil,
        banDurationSeconds = 0,
      ),
      otpLength = spec.otpLength,
      otpResendAfter = spec.otpResendAfterSeconds,
      passkeySettings = PasskeySettingsBody(
        rpId = spec.passkeyRpId,
        rpName = spec.passkeyRpName,
        origins = spec.passkeyOrigins,
        userVerification = spec.passkeyUserVerification,
      ),
      ipHeader = spec.ipHeader,
      acrVocabulary = Some(spec.acrVocabulary),
    )
    send(Method.PUT, central("configuration", "challenges", "challenge-settings"), Some(body.toJson))
      .flatMap(expectSuccess("upsertChallengeSettings", _))

  override def syncConfiguration(): Task[Unit] =
    send(Method.POST, central("service", "configuration", "sync"), None)
      .flatMap(expectSuccess("syncConfiguration", _))

  /** Retried on a 5xx for the reason e2e's client is: the sync makes edge call central over a
    * pooled connection, and one central closed while it sat idle surfaces here as a 500 on the
    * first attempt.
    */
  override def syncEdgeConfiguration(): Task[Unit] =
    val post = send(Method.POST, edge("service", "configuration", "sync"), None, edgeAuthorization)
    post
      .repeat(Schedule.spaced(500.millis) *> Schedule.recurUntil[AdminResponse](!_.status.isServerError))
      .timeout(5.seconds)
      .someOrElseZIO(post)
      .withClock(Clock.ClockLive)
      .flatMap(expectSuccess("syncEdgeConfiguration", _))

  override def flushUserOutbox(): Task[Unit] =
    send(Method.POST, central("service", "users", "outbox", "flush"), None)
      .flatMap(expectSuccess("flushUserOutbox", _))

  private def endpointBody(spec: ResourceEndpointSpec): ResourceEndpointBody =
    ResourceEndpointBody(
      id = spec.id,
      path = spec.path,
      method = spec.method,
      fetchUserInfo = spec.fetchUserInfo,
      allow = spec.allow,
      inject = Nil,
      stepUpCondition = spec.stepUpCondition,
      stepUpAcr = spec.stepUpAcr,
      maxAge = spec.maxAgeSeconds,
    )

  private def central(segments: String*): URL = centralUrl.addPath(Path(segments.mkString("/", "/", "")))

  private def edge(segments: String*): URL = edgeUrl.addPath(Path(segments.mkString("/", "/", "")))

  private def send(
      method: Method,
      url: URL,
      body: Option[String],
      authorization: Authorization = centralAuthorization,
  ): Task[AdminResponse] =
    val base = Request(method = method, url = url, body = body.fold(Body.empty)(Body.fromString(_)))
      .addHeader(authorization)
    val request = body.fold(base)(_ => base.addHeader(Header.ContentType(MediaType.application.json)))
    ZIO.scoped:
      client.request(request).flatMap: response =>
        response.body.asString.map(AdminResponse(response.status, _))

  /** Finishes a create central refused, by re-reading and applying the desired-state update when
    * the id turns out to be there after all.
    *
    * The create-or-update choice is made on a cached listing, so a concurrent run -- or the one
    * that died halfway and is being retried -- can commit the same id between that read and this
    * write. Central reports it as the unique violation's bare `500`, indistinguishable from being
    * broken, so what tells the two apart is the fresh read rather than the status: an id that is
    * there now was committed by someone and the update converges on it, and one that is not
    * leaves the create's failure to stop the run.
    */
  private def convergeAfterFailedCreate[A](
      operation: String,
      response: AdminResponse,
      current: Task[Option[A]],
  )(update: A => Task[Unit]): Task[Unit] =
    if response.status.isSuccess then ZIO.unit
    else current.flatMap(_.fold(expectSuccess(operation, response))(update))

  private def expectSuccess(operation: String, response: AdminResponse): Task[Unit] =
    ZIO.unless(response.status.isSuccess)(ZIO.fail(AdminCallFailed(operation, response.status, response.body))).unit

  private def decode[A: JsonDecoder](operation: String, response: AdminResponse): Task[A] =
    ZIO.fromEither(response.body.fromJson[A])
      .mapError(error => AdminCallFailed(operation, response.status, s"unreadable response: $error"))

object HttpAdminClient:
  /** Effectful because a target URL that does not parse is a configuration error worth failing
    * the run on, rather than one that turns every admin call into a request to a relative URL.
    */
  def make(client: Client, targets: TargetsConfig, provision: ProvisionConfig): Task[AdminClient] =
    for
      central <- parseUrl("central-url", targets.centralUrl)
      edge <- parseUrl("edge-url", targets.edgeUrl)
    yield HttpAdminClient(
      client = client,
      centralUrl = central,
      edgeUrl = edge,
      centralSecret = provision.centralSecret.stringValue,
      edgeSecret = provision.edgeSecret.stringValue,
      tenantId = provision.tenantId,
    )

  private def parseUrl(setting: String, url: String): Task[URL] =
    ZIO.fromEither(URL.decode(url)).mapError(cause => InvalidAdminUrl(setting, url, cause))

  /** Central's admin API is multilingual; a load campaign is not, so every description this
    * client writes carries one tag.
    */
  private val englishTag = "en"
  private val defaultTheme = "default"
  private val defaultOtpTemplateId = "default"
  private val nativeClientType = "native"
  private val webClientType = "web"

  private case class AdminResponse(status: Status, body: String)

  // Request/response bodies, field-for-field central's own DTOs. zio-json omits a `None` member,
  // which is what central's optional-means-absent members expect; the `Json`-typed members are
  // the patch fields, where an explicit `null` is a deletion and omission is "leave alone".

  private case class PatchSet(add: Set[String], remove: Set[String]) derives JsonEncoder

  private case class PatchText(add: Map[String, String], delete: Set[String]) derives JsonEncoder

  private case class CreateClientBody(
      tenantId: String,
      id: String,
      clientName: Map[String, String],
      redirectUris: Set[String],
      allowedScopes: Set[String],
      permissions: Set[String],
      accessTokenTtl: Int,
      refreshTokenTtl: Option[Int],
      theme: String,
      authFlow: Json,
      registrationFlow: Option[Json],
      otpTemplateId: String,
      frontChannelLogoutSessionRequired: Boolean,
      backChannelLogoutUri: Option[String],
      clientType: String,
  ) derives JsonEncoder

  private case class UpdateClientBody(
      clientId: String,
      clientName: Map[String, String],
      redirectUris: PatchSet,
      scope: PatchSet,
      permissions: PatchSet,
      accessTokenTtl: Option[Long],
      refreshTokenTtl: Option[Long],
      theme: Option[String],
      otpTemplateId: Option[String],
      authFlow: Json,
      registrationFlow: Json,
      backChannelLogoutUri: Json,
  ) derives JsonEncoder

  private case class CreateClientResponseBody(secret: Option[String]) derives JsonDecoder

  private case class RotateSecretResponseBody(secret: String) derives JsonDecoder

  private case class ClientListEntry(
      id: String,
      redirectUris: Set[String],
      scope: Set[String],
      permissions: Set[String],
  ) derives JsonDecoder

  /** A client's patched, multi-value state as central's listing reports it -- what a
    * desired-state update has to diff the blueprint against to know what to remove.
    */
  private case class ClientState(redirectUris: Set[String], scopes: Set[String], permissions: Set[String])

  private object ClientState:
    val empty: ClientState = ClientState(Set.empty, Set.empty, Set.empty)

  private case class ClientListBody(clients: List[ClientListEntry]) derives JsonDecoder

  private case class ResourceEndpointBody(
      id: UUID,
      path: String,
      method: String,
      fetchUserInfo: Boolean,
      allow: Option[String],
      inject: List[Json],
      stepUpCondition: Option[String],
      stepUpAcr: Option[String],
      maxAge: Option[Int],
  ) derives JsonEncoder

  private case class CreateResourceBody(
      tenantId: String,
      resourceId: String,
      resource: String,
      audience: List[String],
      endpoints: List[ResourceEndpointBody],
      internal: Boolean,
  ) derives JsonEncoder

  private case class UpdateResourceBody(
      resourceId: String,
      resource: Option[String],
      audience: Option[List[String]],
      deleteEndpoints: Set[UUID],
      createEndpoints: List[ResourceEndpointBody],
  ) derives JsonEncoder

  private case class ResourceListEndpoint(id: UUID) derives JsonDecoder

  private case class ResourceListEntry(resourceId: String, endpoints: List[ResourceListEndpoint]) derives JsonDecoder

  private case class ResourceListBody(resources: List[ResourceListEntry]) derives JsonDecoder

  private case class CreatePermissionBody(
      tenantId: String,
      permission: String,
      description: Map[String, String],
      endpointIds: Set[UUID],
  ) derives JsonEncoder

  private case class UpdatePermissionBody(
      tenantId: String,
      permission: String,
      description: PatchText,
      endpointIds: Option[Set[UUID]],
  ) derives JsonEncoder

  private case class PermissionListEntry(permission: String) derives JsonDecoder

  private case class PermissionListBody(permissions: List[PermissionListEntry]) derives JsonDecoder

  private case class CreateRoleBody(
      tenantId: String,
      id: String,
      description: Map[String, String],
      permissions: Set[String],
  ) derives JsonEncoder

  private case class UpdateRoleBody(
      tenantId: String,
      id: String,
      description: PatchText,
      permissions: PatchSet,
  ) derives JsonEncoder

  private case class RoleListEntry(id: String, permissions: Set[String]) derives JsonDecoder

  private case class RoleListBody(roles: List[RoleListEntry]) derives JsonDecoder

  private case class PresetBody(
      id: String,
      description: String,
      redirectUri: String,
      postLoginRedirectUri: String,
      postLogoutRedirectUri: Option[String],
      scope: Set[String],
      responseType: String,
      customParameters: Map[String, List[String]],
      cookieDomain: Option[String],
      cookiePath: Option[String],
  ) derives JsonEncoder

  private case class SavePresetsBody(clientId: String, presets: List[PresetBody]) derives JsonEncoder

  private case class SubmissionLimitsBody(
      otpRequest: List[Json],
      otpSubmit: List[Json],
      passwordSubmit: List[Json],
      passkeyAssertion: List[Json],
      banDurationSeconds: Int,
  ) derives JsonEncoder

  private case class PasskeySettingsBody(
      rpId: String,
      rpName: String,
      origins: Set[String],
      userVerification: String,
  ) derives JsonEncoder

  private case class UpsertChallengeSettingsBody(
      tenantId: String,
      allowedPrefixes: List[String],
      submissionLimits: SubmissionLimitsBody,
      otpLength: Int,
      otpResendAfter: Int,
      passkeySettings: PasskeySettingsBody,
      ipHeader: String,
      acrVocabulary: Option[Map[String, List[String]]],
  ) derives JsonEncoder

final case class InvalidAdminUrl(setting: String, url: String, cause: Throwable)
    extends RuntimeException(s"targets.$setting is not a valid URL: $url", cause)

/** A central/edge admin call that did not succeed. Carries the operation rather than the URL so
  * that a `provision` failure names the step to re-run.
  */
final case class AdminCallFailed(operation: String, status: Status, body: String)
    extends RuntimeException(s"$operation failed: status=$status body=$body")
