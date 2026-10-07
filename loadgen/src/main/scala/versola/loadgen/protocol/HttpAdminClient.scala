package versola.loadgen.protocol

import versola.loadgen.config.{ProvisionConfig, TargetsConfig}
import versola.util.{ClientAssertion, PrivateJsonWebKey}
import zio.*
import zio.http.*
import zio.http.Header.Authorization
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** [[AdminClient]] over central's admin API as edge proxies it, the way `central-ui` reaches it:
  * every call goes to `/resources/central/...` on edge with a `client_credentials` access token
  * for `resource://central`, and edge -- having checked the token's audience and the client's
  * permissions -- forwards it to central under the resource secret it holds. loadgen therefore
  * holds no internal secret, and needs no route to central at all.
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
    authUrl: URL,
    edgeUrl: URL,
    provisionerClientId: String,
    credential: ProvisionerCredential,
    tenantId: String,
    token: Ref.Synchronized[Option[AccessToken]],
    edgeNonce: Ref[Option[String]],
) extends AdminClient:

  import HttpAdminClient.*

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
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = false,
      backChannelLogoutUri = spec.backChannelLogoutUri,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      consentFlow = None,
      // A campaign's clients never vary DPoP, mTLS, JAR/PAR or an edge signing key -- see
      // AdminSpecs.ClientSpec -- but the DTO requires every one of these named regardless.
      dpopBoundAccessTokens = false,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = if spec.publicClient then publicAuthMethod else clientSecretAuthMethod,
      mtlsAuth = None,
      certificateBoundAccessTokens = false,
      jwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = false,
      edgeSigningKey = None,
      template = None,
      applicationType = applicationType(spec),
      issueEdgeClientCertificate = false,
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
      // Written on the update too, so a client a previous configuration left bound to a
      // certificate or narrowed to one proof algorithm converges on the blueprint instead of
      // keeping a setting the campaign cannot satisfy.
      certificateBoundAccessTokens = false,
      dpopSigningAlgs = Set.empty,
      dpopBoundAccessTokens = false,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = false,
      // Written on the update too, on the same convergence terms as the resets above -- a
      // client a previous configuration left `native` (or vice versa) converges on the
      // blueprint's type rather than keeping whatever it registered with.
      applicationType = applicationType(spec),
    )
    send(Method.PUT, central("configuration", "clients"), Some(body.toJson))
      .flatMap(expectSuccess("updateClient", _))

  /** OIDC Registration §2 `application_type`, as central's `ApplicationType` enum encodes it:
    * `spec.publicClient` is the same PKCE-only-vs-confidential distinction [[ClientSpec]] names
    * it for (see its scaladoc), just under central's own vocabulary. */
  private def applicationType(spec: ClientSpec): String =
    if spec.publicClient then nativeApplicationType else webApplicationType

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
        case Some(state) => updateResource(spec, state)

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
      convergeAfterFailedCreate("registerResource", response, listResources.map(_.get(spec.resourceId))): state =>
        updateResource(spec, state)

  /** Endpoints are replaced by id, and central's update deletes every id it is about to create
    * before creating it, so sending the full desired set is a single atomic desired-state apply:
    * an endpoint whose ACR or CEL rule changed is rewritten, and one the blueprint dropped is
    * deleted rather than left granting access nothing names any more.
    *
    * Audience is patched like a client's scope or a role's permissions -- central stores it as a
    * list, not a set, and replays `add` after `remove` on top of what it already has (see
    * `PatchAudience.patch`), so sending the full desired list back as `add` would duplicate every
    * client already in it instead of leaving the list untouched.
    */
  private def updateResource(spec: ResourceSpec, existing: ResourceState): Task[Unit] =
    val desired = spec.endpoints.map(_.id).toSet
    val desiredAudience = spec.audience.toSet
    val body = UpdateResourceBody(
      resourceId = spec.resourceId,
      resource = Some(spec.resourceUri),
      audience = PatchAudience(
        add = desiredAudience -- existing.audience,
        remove = existing.audience -- desiredAudience,
      ),
      deleteEndpoints = existing.endpointIds -- desired,
      createEndpoints = spec.endpoints.map(endpointBody),
    )
    send(Method.PUT, central("configuration", "resources"), Some(body.toJson))
      .flatMap(expectSuccess("updateResource", _))

  private def listResources: Task[Map[String, ResourceState]] =
    val url = central("configuration", "resources").addQueryParam("tenantId", tenantId)
    send(Method.GET, url, None).flatMap: response =>
      expectSuccess("listResources", response)
        *> decode[ResourceListBody]("listResources", response)
          .map(_.resources.map(entry => entry.resourceId -> ResourceState(entry.audience.toSet, entry.endpoints.map(_.id).toSet)).toMap)

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
      securityProfile = spec.securityProfile,
    )
    send(Method.PUT, central("configuration", "challenges", "challenge-settings"), Some(body.toJson))
      .flatMap(expectSuccess("upsertChallengeSettings", _))

  /** Retried on a 5xx for the reason e2e's client is: the sync makes central call auth over a
    * pooled connection, and one auth closed while it sat idle surfaces here as a 500 on the
    * first attempt.
    */
  override def syncConfiguration(): Task[Unit] =
    val post = send(Method.POST, central("service", "configuration", "sync"), None)
    post
      .repeat(Schedule.spaced(500.millis) *> Schedule.recurUntil[AdminResponse](!_.status.isServerError))
      .timeout(5.seconds)
      .someOrElseZIO(post)
      .withClock(Clock.ClockLive)
      .flatMap(expectSuccess("syncConfiguration", _))

  /** Edge has no sync a caller outside the cluster can reach, and publishing one would mean
    * giving loadgen an internal secret again. What it does instead is wait for edge's own
    * `configuration-cache-refresh-interval` to come round, and prove it has by reading one of
    * the campaign's own endpoints back through the proxy -- a 404 from edge means the resource
    * is still absent from the cache the campaign's traffic will be authorized against.
    *
    * Probed at `method`/`path`, not at the resource's bare root: edge's proxy only recognizes a
    * loaded resource once the rest-of-path also matches a registered endpoint, and no campaign
    * resource registers one at `/`, so a root probe 404s identically cached or not and this
    * would otherwise never observe the resource landing. The provisioner's own token has no
    * permission on the endpoint it probes, so a resource that has landed answers 403 rather than
    * 200 -- either is proof enough, since only "still missing" answers 404.
    *
    * Bounded rather than open-ended: a deployment whose interval is longer than this fails the
    * run with the step named, which is a better answer than a campaign measuring 403s.
    */
  override def awaitEdgeConfiguration(resourceId: String, method: String, path: String): Task[Unit] =
    val probe = send(Method.fromString(method), edgeUrl.addPath(Path(s"/resources/$resourceId$path")), None)
    probe
      .repeat(Schedule.spaced(2.seconds) *> Schedule.recurUntil[AdminResponse](_.status != Status.NotFound))
      .timeout(edgeCacheTimeout)
      .withClock(Clock.ClockLive)
      .someOrFail(EdgeConfigurationStale(resourceId, edgeCacheTimeout))
      .unit

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

  /** Central's path as edge exposes it: the proxy matches the rest-of-path against the endpoint
    * catalog `BootstrapService` registered for the `central` resource, so these are the same
    * paths the direct API has, one prefix deeper.
    */
  private def central(segments: String*): URL =
    edgeUrl.addPath(Path(("resources" +: "central" +: segments).mkString("/", "/", "")))

  private def send(
      method: Method,
      url: URL,
      body: Option[String],
  ): Task[AdminResponse] =
    def attempt(accessToken: AccessToken, nonce: Option[String]): Task[Received] =
      for
        authorization <- authorize(accessToken, method, url, nonce)
        base = Request(method = method, url = url, body = body.fold(Body.empty)(Body.fromString(_)))
          .addHeaders(authorization)
        request = body.fold(base)(_ => base.addHeader(Header.ContentType(MediaType.application.json)))
        received <- exchange(request)
      yield received

    // RFC 9449 §9: edge may demand a nonce of a DPoP-bound call, and hands one back on any
    // response. One is held for the run and retried with once, as `EdgeActionClient` does.
    def withNonce(accessToken: AccessToken): Task[AdminResponse] =
      for
        held <- edgeNonce.get
        first <- attempt(accessToken, held)
        received <- first.nonceChallenge(Status.Unauthorized) match
          case Some(issued) => edgeNonce.set(Some(issued)) *> attempt(accessToken, Some(issued))
          case None => ZIO.succeed(first)
        _ <- ZIO.foreachDiscard(received.nonce)(issued => edgeNonce.set(Some(issued)))
      yield received.response

    // A campaign's provisioning outlives a token whose TTL the deployment chose, so an expiry
    // is re-authenticated once rather than failing the run. Anything else 401 means is a
    // configuration problem the retry would repeat, and the second response is what surfaces.
    for
      accessToken <- currentAccessToken
      response <- withNonce(accessToken)
      retried <-
        if response.status == Status.Unauthorized then token.set(None) *> currentAccessToken.flatMap(withNonce)
        else ZIO.succeed(response)
    yield retried

  /** Edge picks the path off the `Authorization` scheme, so a DPoP-bound token is sent under
    * `DPoP` with a proof naming it (`ath`) -- presented as a bearer token, edge refuses it as a
    * downgrade. `htu` is the URL dialled, the caveat `EdgeActionClient.sign` carries.
    */
  private def authorize(accessToken: AccessToken, method: Method, url: URL, nonce: Option[String]): Task[Headers] =
    credential match
      case ProvisionerCredential.ClientSecret(_) => ZIO.succeed(Headers(Authorization.Bearer(accessToken.value)))
      case ProvisionerCredential.PrivateKeyJwt(_, key) =>
        key.proof(method, url.copy(queryParams = QueryParams.empty, fragment = None).encode, Some(accessToken), nonce)
          .mapError(signingFailed)
          .map: proof =>
            Headers(
              Header.Custom(Authorization.name, s"$dpopScheme ${accessToken.value}"),
              Header.Custom(HttpAuthClient.dpopHeader, proof),
            )

  private def currentAccessToken: Task[AccessToken] =
    token.modifyZIO:
      case Some(existing) => ZIO.succeed((existing, Some(existing)))
      case None => requestAccessToken.map(fresh => (fresh, Some(fresh)))

  /** RFC 8707 `resource`: the token is bound to `resource://central` specifically, so a leaked
    * one opens central's admin API and nothing else edge fronts.
    */
  private def requestAccessToken: Task[AccessToken] =
    val grant = Form(
      FormField.simpleField("grant_type", "client_credentials"),
      FormField.simpleField("resource", centralResourceUri),
    )
    val endpoint = authUrl.addPath(Path.root / "token")
    val received = credential match
      case ProvisionerCredential.ClientSecret(secret) =>
        exchange(
          Request
            .post(endpoint, Body.fromURLEncodedForm(grant))
            .addHeader(Authorization.Basic(provisionerClientId, secret)),
        )
      case ProvisionerCredential.PrivateKeyJwt(signing, key) =>
        // A fresh assertion per attempt: auth remembers every `jti` it accepted, so the retry a
        // nonce challenge asks for cannot resend the first one.
        def attempt(nonce: Option[String]): Task[Received] =
          for
            assertion <- ClientAssertion.issue(
              clientId = provisionerClientId,
              audience = issuer,
              algorithm = signing.algorithm,
              keyId = signing.keyId,
              privateKey = signing.privateKey,
            )
            proof <- key.proof(Method.POST, endpoint.encode, None, nonce).mapError(signingFailed)
            form = grant
              .append(FormField.simpleField("client_id", provisionerClientId))
              .append(FormField.simpleField("client_assertion_type", ClientAssertion.Type))
              .append(FormField.simpleField("client_assertion", assertion))
            received <- exchange(
              Request
                .post(endpoint, Body.fromURLEncodedForm(form))
                .addHeader(Header.Custom(HttpAuthClient.dpopHeader, proof)),
            )
          yield received

        attempt(None).flatMap: first =>
          first.nonceChallenge(Status.BadRequest).fold(ZIO.succeed(first))(issued => attempt(Some(issued)))
    received.flatMap: received =>
      expectSuccess("authenticate", received.response)
        *> decode[TokenResponseBody]("authenticate", received.response).map(token => AccessToken(token.accessToken))

  /** FAPI 2.0 §5.3.2.1-8: an assertion's `aud` is the issuer alone. Taken to be the auth URL
    * dialled, under the same assumption `HttpAuthClient.tokenHtu` makes. */
  private def issuer: String = authUrl.encode.stripSuffix("/")

  private def exchange(request: Request): Task[Received] =
    ZIO.scoped:
      client.request(request).flatMap: response =>
        response.body.asString.map: body =>
          Received(
            AdminResponse(response.status, body),
            response.rawHeader(HttpAuthClient.dpopNonceHeader),
            response.rawHeader("WWW-Authenticate"),
          )

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
      auth <- parseUrl("auth-url", targets.authUrl)
      edge <- parseUrl("edge-url", targets.edgeUrl)
      credential <- credentialOf(provision)
      token <- Ref.Synchronized.make(Option.empty[AccessToken])
      edgeNonce <- Ref.make(Option.empty[String])
    yield HttpAdminClient(
      client = client,
      authUrl = auth,
      edgeUrl = edge,
      provisionerClientId = provision.provisionerClientId,
      credential = credential,
      tenantId = provision.tenantId,
      token = token,
      edgeNonce = edgeNonce,
    )

  /** The private key when one is configured, the secret otherwise -- see [[ProvisionConfig]].
    *
    * The DPoP key is generated per run rather than derived like [[DpopKeyPool]]'s: the tokens it
    * binds live no longer than this process, so nothing has to reproduce it.
    */
  private[protocol] def credentialOf(provision: ProvisionConfig): Task[ProvisionerCredential] =
    (provision.provisionerPrivateKey, provision.provisionerSecret) match
      case (Some(privateKey), _) =>
        for
          signing <- ZIO
            .fromEither:
              privateKey.stringValue.fromJson[Json.Obj]
                .left.map(error => s"must be a JWK: $error")
                .flatMap(PrivateJsonWebKey.validate)
                .flatMap(_.signing)
            .mapError(reason => InvalidProvisionerCredential(s"provision.provisioner-private-key $reason"))
          pool <- DpopKeyPool.derive(UUID.randomUUID().toString, size = 1).mapError(signingFailed)
        yield ProvisionerCredential.PrivateKeyJwt(signing, pool.keyFor(0))
      case (None, Some(secret)) => ZIO.succeed(ProvisionerCredential.ClientSecret(secret.stringValue))
      case (None, None) =>
        ZIO.fail(InvalidProvisionerCredential("provision needs a provisioner-private-key or a provisioner-secret"))

  private def signingFailed(error: ProtocolError): Throwable =
    InvalidProvisionerCredential(s"could not sign as the provisioner: $error")

  private val dpopScheme = "DPoP"

  private def parseUrl(setting: String, url: String): Task[URL] =
    ZIO.fromEither(URL.decode(url)).mapError(cause => InvalidAdminUrl(setting, url, cause))

  /** Central's admin API is multilingual; a load campaign is not, so every description this
    * client writes carries one tag.
    */
  private val englishTag = "en"
  private val defaultTheme = "default"
  private val defaultOtpTemplateId = "default"
  private val publicAuthMethod = "none"
  private val clientSecretAuthMethod = "client_secret"
  private val nativeApplicationType = "native"
  private val webApplicationType = "web"

  /** The internal resource id `BootstrapService` seeds central's admin API under; the RFC 8707
    * identifier of an internal resource is `resource://<id>`, which is what edge's proxy checks
    * the token's audience against. */
  private val centralResourceUri = "resource://central"

  /** Long enough to outlast the five minutes `scripts/gen-env.scala` gives a deployed
    * environment, since that is the interval edge's caches actually run on outside local. */
  private val edgeCacheTimeout = 6.minutes

  private case class AdminResponse(status: Status, body: String)

  private case class Received(response: AdminResponse, nonce: Option[String], challenge: Option[String]):
    /** RFC 9449 §9's demand, and the nonce to retry with: a `400` `use_dpop_nonce` body at auth's
      * `/token`, a `401` `WWW-Authenticate: DPoP error="use_dpop_nonce"` at edge. */
    def nonceChallenge(status: Status): Option[String] =
      val demanded = response.status == status &&
        (challenge.exists(_.contains(s"error=\"${HttpAuthClient.useDpopNonce}\"")) ||
          response.body.contains(s"\"${HttpAuthClient.useDpopNonce}\""))
      if demanded then nonce else None

  private case class TokenResponseBody(@jsonField("access_token") accessToken: String) derives JsonDecoder

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
      frontChannelLogoutUri: Option[String],
      frontChannelLogoutSessionRequired: Boolean,
      backChannelLogoutUri: Option[String],
      logoUri: Option[String],
      policyUri: Option[String],
      tosUri: Option[String],
      consentFlow: Option[Json],
      // None of these carry a Scala default on central's `CreateClientRequest`: a create
      // request is a full snapshot, and a campaign that never varies a setting still has to
      // say so, rather than lean on a default the DTO does not offer.
      dpopBoundAccessTokens: Boolean,
      dpopSigningAlgs: Set[String],
      dpopMinRsaKeySize: Option[Int],
      authMethod: String,
      mtlsAuth: Option[Json],
      certificateBoundAccessTokens: Boolean,
      jwks: Option[Json],
      requireSignedRequestObject: Boolean,
      requirePushedAuthorizationRequests: Boolean,
      edgeSigningKey: Option[Json],
      template: Option[Json],
      applicationType: String,
      issueEdgeClientCertificate: Boolean,
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
      certificateBoundAccessTokens: Boolean,
      dpopSigningAlgs: Set[String],
      dpopBoundAccessTokens: Boolean,
      requireSignedRequestObject: Boolean,
      requirePushedAuthorizationRequests: Boolean,
      applicationType: String,
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

  private case class PatchAudience(add: Set[String], remove: Set[String]) derives JsonEncoder

  private case class UpdateResourceBody(
      resourceId: String,
      resource: Option[String],
      audience: PatchAudience,
      deleteEndpoints: Set[UUID],
      createEndpoints: List[ResourceEndpointBody],
  ) derives JsonEncoder

  private case class ResourceListEndpoint(id: UUID) derives JsonDecoder

  private case class ResourceListEntry(
      resourceId: String,
      audience: List[String],
      endpoints: List[ResourceListEndpoint],
  ) derives JsonDecoder

  private case class ResourceListBody(resources: List[ResourceListEntry]) derives JsonDecoder

  /** A resource's patched, multi-value state as central's listing reports it -- what a
    * desired-state update has to diff the blueprint against to know what to add and remove,
    * the same role [[ClientState]] plays for a client.
    */
  private case class ResourceState(audience: Set[String], endpointIds: Set[UUID])

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
      securityProfile: String,
  ) derives JsonEncoder

final case class InvalidAdminUrl(setting: String, url: String, cause: Throwable)
    extends RuntimeException(s"targets.$setting is not a valid URL: $url", cause)

final case class InvalidProvisionerCredential(reason: String) extends RuntimeException(reason)

/** How the provisioner authenticates at auth's `/token`, and how it then presents the token. */
enum ProvisionerCredential:
  case ClientSecret(secret: String)
  /** RFC 7523 `private_key_jwt`, with the token DPoP-bound (RFC 9449) to `key`. */
  case PrivateKeyJwt(signing: PrivateJsonWebKey.Signing, key: DpopKey)

/** A central/edge admin call that did not succeed. Carries the operation rather than the URL so
  * that a `provision` failure names the step to re-run.
  */
final case class AdminCallFailed(operation: String, status: Status, body: String)
    extends RuntimeException(s"$operation failed: status=$status body=$body")

final case class EdgeConfigurationStale(resourceId: String, waited: Duration)
    extends RuntimeException(
      s"edge still does not serve resource '$resourceId' after $waited -- its " +
        s"configuration-cache-refresh-interval is longer than provision waits",
    )
