package versola.edge

import versola.edge.login.{LoginRecord, LoginRepository}
import versola.edge.model.{AccessToken, AccessTokenClaims, AccessTokenId, AuthConversationNotFound, AuthorizationPreset, ClientId, Code, CodeVerifier, InjectRule, InjectTarget, InvalidLogoutToken, PermissionId, PresetId, PresetNotFound, RefreshToken, Resource, ResourceEndpoint, ResourceEndpointId, ResourceId, RoleId, SessionId, State, TenantId, TokenResponse}
import versola.edge.session.{EdgeSessionRecord, EdgeSessionRepository}
import versola.util.cel.CelEvaluator
import versola.util.http.Observability
import versola.util.{Base64, Base64Url, EnvName, JWT, JsonJava, RedirectUri, Secret, SecureRandom, SecurityService}
import zio.http.{Body, Client, Cookie, Header, MediaType, Path, Request, Response, Status, URL}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec, JsonDecoder, jsonField}
import zio.{Chunk, Clock, Duration, IO, NonEmptyChunk, Task, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.{MapHasAsJava, SeqHasAsJava}

trait EdgeService:
  def authorize(
      presetId: PresetId,
      overrideParams: Map[String, String] = Map.empty,
  ): IO[Throwable | PresetNotFound, URL]

  def complete(
      code: Code,
      state: State,
  ): IO[Throwable | AuthConversationNotFound, EdgeService.LoginCompletion]

  def completeError(
      state: State,
      error: String,
      errorDescription: Option[String],
      errorUri: Option[String],
  ): IO[Throwable | AuthConversationNotFound, URL]

  def proxy(
      resourceId: ResourceId,
      restPath: Path,
      request: Request,
  ): Task[Response]

  def getMyPermissions(
      claims: PermissionsClaims,
      resourceIds: List[ResourceId],
  ): UIO[EdgeService.PermissionsResponse]

  /** OP-initiated front-channel logout: drops the edge sessions tied to the SSO
    * session and returns a clearing cookie for every preset whose EDGE_SESSION
    * cookie may have been derived from it. `iss` is the only credential this
    * unauthenticated endpoint has; when it doesn't match the configured OP, the
    * request is silently ignored (no cookies cleared) rather than failing.
    */
  def frontChannelLogout(iss: String, sid: SessionId): Task[List[Cookie.Response]]

  /** OP-initiated back-channel logout: validates the OP-signed `logout_token` and drops
    * the edge sessions tied to the SSO session it names. Server-to-server, so no cookie
    * can be cleared; dropping the session rows is what stops the EDGE_SESSION cookie
    * from being honoured on the next request.
    */
  def backChannelLogout(logoutToken: String): IO[Throwable | InvalidLogoutToken, Unit]

object EdgeService:
  case class LoginCompletion(
      presetId: PresetId,
      accessToken: AccessToken,
      cookieTtl: Duration,
      postLoginRedirectUri: RedirectUri,
      cookieDomain: Option[String],
      cookiePath: Option[String],
  )

  case class ClientNotFound(clientId: ClientId)
    extends RuntimeException(s"OAuth client not found in cache: $clientId")

  /** The `jti` and `sid` claims of the access token. Both are minted by auth
    * for every token issued to an authenticated session, so neither is optional.
    */
  private case class TokenIds(
      @jsonField("jti") accessTokenId: AccessTokenId,
      @jsonField("sid") sessionId: SessionId,
  ) derives JsonCodec

  private val BackChannelLogoutEvent = "http://schemas.openid.net/event/backchannel-logout"

  /** The claims of an OIDC Back-Channel Logout token (spec §2.4) the edge validates and
    * acts on. `sid` is optional in the spec, but the edge indexes its sessions by it and
    * so cannot act on a token without one.
    */
  private case class LogoutTokenClaims(
      @jsonField("iss") issuer: String,
      @jsonField("aud") audience: List[ClientId],
      @jsonField("sid") sessionId: Option[SessionId],
      nonce: Option[String],
      events: Map[String, Json],
  ) derives JsonDecoder

  case class ResourcePermissions(
      permissions: Set[PermissionId],
  ) derives JsonCodec

  case class PermissionsResponse(
      resources: Map[ResourceId, ResourcePermissions],
      /** Lets the console hide affordances that only exist outside production,
        * such as revealing a generated temporary password.
        */
      isProd: Boolean,
  ) derives JsonCodec

  def live: ZLayer[
    OAuthClientService & ResourceService & CelEvaluator & SecureRandom & LoginRepository & SSOClient & SecurityService & Client & EdgeConfig & session.EdgeSessionRepository & JwksService & PermissionService & EnvName,
    Nothing,
    EdgeService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _))

  class Impl(
      clientService: OAuthClientService,
      resourceService: ResourceService,
      celEvaluator: CelEvaluator,
      secureRandom: SecureRandom,
      loginRepository: LoginRepository,
      ssoClient: SSOClient,
      securityService: SecurityService,
      httpClient: Client,
      config: EdgeConfig,
      sessionRepository: EdgeSessionRepository,
      jwksService: JwksService,
      permissionService: PermissionService,
      env: EnvName,
  ) extends EdgeService:

    private val loginTtl = 10.minutes
    private val encryptionKey = SecretKeySpec(config.security.tokenEncryption.key, "AES")
    private val edgeAudience = "resource://edge"

    /** Extra time the edge_sessions row is kept alive past the cookie/token expiry,
      * so the session record is never deleted before the cookie has actually expired.
      */
    private val sessionExpiryGracePeriod = 10.seconds

    override def authorize(
        presetId: PresetId,
        overrideParams: Map[String, String] = Map.empty,
    ): IO[Throwable | PresetNotFound, URL] =
      clientService.findPreset(presetId).someOrFail(PresetNotFound()).flatMap(prepareAuthorizeUrl(_, overrideParams))

    private def prepareAuthorizeUrl(preset: AuthorizationPreset, overrideParams: Map[String, String]): zio.Task[URL] =
      for
        codeVerifier <- secureRandom.nextBytes(32).map(CodeVerifier.fromBytes)

        codeChallenge = Base64.urlEncode:
          MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.getBytes(StandardCharsets.UTF_8))

        state <- secureRandom.nextBytes(16).map(State.fromBytes)

        _ <- loginRepository.create(
          record = LoginRecord(
            codeVerifier = codeVerifier,
            presetId = preset.id,
            state = state,
          ),
          ttl = loginTtl,
        )

        authUrl <- ssoClient.authorizeUri(preset, codeChallenge, state, overrideParams)
      yield authUrl

    override def complete(
        code: Code,
        state: State,
    ): IO[Throwable | AuthConversationNotFound, LoginCompletion] =
      for
        record <- loginRepository.findByState(state).someOrFail(AuthConversationNotFound())
        preset <- clientService.findPreset(record.presetId).someOrFail(AuthConversationNotFound())
        client <- clientService.findClient(preset.clientId).someOrFail(ClientNotFound(preset.clientId))
        tokens <- ssoClient.exchangeAuthorizationCode(
          code = code,
          codeVerifier = record.codeVerifier,
          redirectUri = preset.redirectUri,
          clientId = client.id,
          clientSecret = client.secret,
        )
        cookieTtl = Duration.fromSeconds(tokens.refreshTokenExpiresIn.getOrElse(tokens.expiresIn))
        _ <- storeSession(tokens, preset.id, cookieTtl)
        _ <- loginRepository.deleteByState(state)
      yield LoginCompletion(
        presetId = preset.id,
        accessToken = tokens.accessToken,
        cookieTtl = cookieTtl,
        postLoginRedirectUri = preset.postLoginRedirectUri,
        cookieDomain = preset.cookieDomain,
        cookiePath = preset.cookiePath,
      )

    override def completeError(
        state: State,
        error: String,
        errorDescription: Option[String],
        errorUri: Option[String],
    ): IO[Throwable | AuthConversationNotFound, URL] =
      for
        record <- loginRepository.findByState(state).someOrFail(AuthConversationNotFound())
        preset <- clientService.findPreset(record.presetId).someOrFail(AuthConversationNotFound())
        _ <- loginRepository.deleteByState(state)
      yield preset.postLoginRedirectUri.toUrl.addQueryParams(
        List("error" -> error) ++
          errorDescription.map("error_description" -> _) ++
          errorUri.map("error_uri" -> _),
      )

    /** Records this preset's participation in the SSO session. Written on every login
      * and rotation regardless of whether a refresh token was issued, since logout has
      * to clear the EDGE_SESSION cookie of presets that never received one.
      */
    private def storeSession(
        tokens: TokenResponse,
        presetId: PresetId,
        cookieTtl: Duration,
    ): zio.Task[Unit] =
      for
        now <- Clock.instant
        ids <- extractTokenIds(tokens)
        _ <- ZIO.foreachDiscard(tokens.refreshToken)(Observability.setRefreshToken)
        encryptedRefreshToken <- ZIO.foreach(tokens.refreshToken)(encryptRefreshToken)
        _ <- sessionRepository.create(
          EdgeSessionRecord(
            publicSessionId = ids.sessionId,
            presetId = presetId,
            accessTokenId = ids.accessTokenId,
            encryptedRefreshToken = encryptedRefreshToken,
            expiresAt = now.plusSeconds(cookieTtl.toSeconds + sessionExpiryGracePeriod.toSeconds),
          ),
        )
      yield ()

    /** Extracts the access token's `jti` and `sid` claims together, since every caller
      * that needs one needs the other to build an [[session.EdgeSessionRecord]]. Both
      * claims are minted by auth for every token issued to an authenticated session.
      */
    private def extractTokenIds(tokens: TokenResponse): Task[EdgeService.TokenIds] =
      jwksService.getPublicKeys
        .flatMap(keys => JWT.deserialize[EdgeService.TokenIds](tokens.accessToken, keys, JWT.Type.AccessToken))
        .mapError(e => new RuntimeException(s"Failed to validate JWT: $e"))

    private def encryptRefreshToken(refreshToken: RefreshToken): Task[Secret] =
      securityService.encryptAes256(
        refreshToken.getBytes(StandardCharsets.UTF_8),
        encryptionKey,
      ).map(Secret(_))

    private def decryptRefreshToken(encrypted: Secret): Task[RefreshToken] =
      securityService.decryptAes256(encrypted, encryptionKey)
        .map(bytes => RefreshToken(new String(bytes, StandardCharsets.UTF_8)))

    override def proxy(
        resourceId: ResourceId,
        restPath: Path,
        request: Request,
    ): Task[Response] =
      proxyInternal(resourceId, restPath, request).catchAll:
        case Outcome.Unauthorized => ZIO.succeed(Response.unauthorized)
        case Outcome.Forbidden => ZIO.succeed(Response.forbidden)
        case Outcome.NotFound => ZIO.succeed(Response.notFound)
        case Outcome.InternalServerError => ZIO.succeed(Response.internalServerError)
        case Outcome.Reauthenticate(uri, clear) =>
          ZIO.succeed(
            Response.status(Status.Unauthorized)
              .addHeader(Header.Location(uri))
              .addCookie(clear),
          )
        case Outcome.InsufficientAuthentication(acr, maxAge) =>
          val params = List(
            Some("""error="insufficient_user_authentication""""),
            acr.map(v => s"""acr_values="$v""""),
            maxAge.map(v => s"""max_age="$v""""),
          ).flatten.mkString(", ")
          ZIO.succeed(
            Response.status(Status.Unauthorized)
              .addHeader(Header.Custom("WWW-Authenticate", s"Bearer $params")),
          )
        case ex: Throwable => ZIO.fail(ex)

    override def getMyPermissions(
        claims: PermissionsClaims,
        resourceIds: List[ResourceId],
    ): UIO[PermissionsResponse] =
      val tenantId = claims.tenantId
      val roles = claims.roles.getOrElse(List.empty)
      val rolesMap = tenantId.fold(Map.empty[TenantId, List[RoleId]])(tid => Map(tid -> roles))

      ZIO.foreach(resourceIds): resourceId =>
        for
          resource <- resourceService.findByResourceId(resourceId)
          endpointIds = resource.fold(Set.empty[ResourceEndpointId])(_.endpoints.map(_.id).toSet)
          perms <- permissionService.getPermissionsForRoles(rolesMap, endpointIds)
        yield Some(resourceId -> ResourcePermissions(perms))
      .map(entries => PermissionsResponse(entries.flatten.toMap, env.isProd))

    override def frontChannelLogout(iss: String, sid: SessionId): Task[List[Cookie.Response]] =
      if !URL.decode(iss).toOption.exists(sameOrigin(_, config.versolaUrl)) then
        ZIO.succeed(Nil)
      else
        for
          records <- sessionRepository.deleteBySessionId(sid)
          presets <- clientService.listPresets(records.map(_.presetId).distinct)
        yield presets.map(preset => EdgeSessionCookie.clear(preset.cookieDomain, preset.cookiePath))

    override def backChannelLogout(logoutToken: String): IO[Throwable | InvalidLogoutToken, Unit] =
      for
        publicKeys <- jwksService.getPublicKeys
        claims <- JWT.deserialize[EdgeService.LogoutTokenClaims](logoutToken, publicKeys, JWT.Type.JWT)
          .mapError(error => InvalidLogoutToken(s"logout token is not a valid JWT: $error"))
        _ <- validateLogoutToken(claims)
        sid <- ZIO.fromOption(claims.sessionId)
          .orElseFail(InvalidLogoutToken("logout token carries no sid claim"))
        _ <- sessionRepository.deleteBySessionId(sid)
      yield ()

    /** OIDC Back-Channel Logout §2.6: the token must come from the configured OP, be
      * addressed to a client this edge knows and carry the logout event. A `nonce` marks
      * it as a replayed id token rather than a logout token, so it is rejected.
      */
    private def validateLogoutToken(claims: EdgeService.LogoutTokenClaims): IO[InvalidLogoutToken, Unit] =
      for
        _ <- ZIO.fail(InvalidLogoutToken("logout token was issued by an unknown issuer"))
          .unless(URL.decode(claims.issuer).toOption.exists(sameOrigin(_, config.versolaUrl)))
        knownAudience <- ZIO.exists(claims.audience)(clientService.findClient(_).map(_.isDefined))
        _ <- ZIO.fail(InvalidLogoutToken("logout token is not addressed to a known client"))
          .unless(knownAudience)
        _ <- ZIO.fail(InvalidLogoutToken("logout token does not carry the back-channel logout event"))
          .unless(claims.events.contains(EdgeService.BackChannelLogoutEvent))
        _ <- ZIO.fail(InvalidLogoutToken("logout token must not carry a nonce"))
          .when(claims.nonce.isDefined)
      yield ()

    /** Compares two URLs by origin (scheme, host, port), tolerating equivalent but
      * textually different representations: host casing and an explicit default port
      * (e.g. `:443` on `https`) rather than requiring a byte-for-byte string match.
      */
    private def sameOrigin(a: URL, b: URL): Boolean =
      a.scheme.map(_.encode.toLowerCase) == b.scheme.map(_.encode.toLowerCase) &&
        a.host.map(_.toLowerCase) == b.host.map(_.toLowerCase) &&
        a.portOrDefault == b.portOrDefault

    private def proxyInternal(
        resourceId: ResourceId,
        restPath: Path,
        request: Request,
    ): IO[Throwable | Outcome, Response] =
      for
        (accessToken, authSource) <- extractAccessToken(request)
        publicKeys <- jwksService.getPublicKeys
        now <- Clock.instant
        session <- JWT.deserialize[Json.Obj](accessToken, publicKeys, JWT.Type.AccessToken)
          .foldZIO(
            {
              case JWT.Error.Expired(jti) =>
                authSource match
                  case AuthSource.Cookie(presetId) => refreshSession(AccessTokenId(jti), presetId, now)
                  case AuthSource.Header => ZIO.fail(Outcome.Unauthorized)
              case _ =>
                ZIO.fail(Outcome.Unauthorized)
            },
            claims => ZIO.succeed(ActiveSession(accessToken, claims, None)),
          )
        _ <- logAccessTokenClaims(session.claims)

        resource <- resourceService.findByResourceId(resourceId).someOrFail(Outcome.NotFound)
        endpoint <- findEndpoint(resource.endpoints, request.method.name, restPath)
        // the registered template, not the concrete path: a parameterized endpoint would
        // otherwise produce one metric time series per parameter value
        _ <- Observability.setRoutePath(s"/resources/$resourceId${endpoint.path}")
        parsedBody <- readJsonBody(request)
        typedClaims <- checkPermissions(session.claims, endpoint, request, parsedBody)
        _ <- checkAudience(resource, typedClaims)
        userInfo <- ssoClient.userInfo(session.accessToken)
          .when(endpoint.fetchUserInfo)
          .someOrElse(Json.Obj())
          .mapError {
            case SSOClient.UserInfoUnauthorized => Outcome.Unauthorized
            case _: Throwable => Outcome.InternalServerError
          }
        celContext <- checkRules(session.claims, userInfo, request, endpoint, restPath, parsedBody)
        _ <- checkStepUp(endpoint, typedClaims, now, celContext)
        upstream <- buildUpstreamRequest(resource, endpoint, restPath, request, parsedBody, session.accessToken, celContext)
        response <- ZIO.scoped(httpClient.request(upstream))
        stripped = response.removeHeader(Header.SetCookie)
      yield session.rotatedCookie.fold(stripped)(stripped.addCookie)

    private enum AuthSource:
      case Cookie(presetId: PresetId)
      case Header

    private def extractAccessToken(request: Request): IO[Outcome, (AccessToken, AuthSource)] =
      ZIO.fromOption(
        request.header(Header.Authorization)
          .collect { case Header.Authorization.Bearer(token) =>
            (AccessToken(token.stringValue), AuthSource.Header)
          }
          .orElse(
            request.cookie(EdgeSessionCookie.name).map { cookie =>
              val (presetId, token) = EdgeSessionCookie.parse(cookie.content)
              (token, AuthSource.Cookie(presetId))
            },
          ),
      ).orElseFail(Outcome.Unauthorized)

    /** Best-effort: annotates the request's `session_id`/`user_id`/`token` as soon as the
      * access token's claims are known, so every subsequent log line (including one from
      * [[checkPermissions]] failing) carries them. Decode failures are swallowed here since
      * [[checkPermissions]] re-validates the same claims and turns a failure into
      * `Outcome.Unauthorized`.
      */
    private def logAccessTokenClaims(claims: Json.Obj): UIO[Unit] =
      claims.as[AccessTokenClaims].fold(
        _ => ZIO.unit,
        typed =>
          Observability.setToken(typed.jti) *>
            Observability.setUserId(typed.subject) *>
            ZIO.foreachDiscard(typed.sid)(Observability.setSessionId),
      )

    private def checkPermissions(
        claims: Json.Obj,
        endpoint: ResourceEndpoint,
        request: Request,
        parsedBody: Option[Json],
    ): IO[Outcome, AccessTokenClaims] =
      for
        typed <- ZIO.fromEither(claims.as[AccessTokenClaims]).orElseFail(Outcome.Unauthorized)
        isServiceToken = typed.subject == typed.clientId

        allowed <-
          if isServiceToken then
            permissionService.getAllowedEndpointsForClient(typed.clientId)
          else
            permissionService.getAllowedEndpointsForRoles(typed.tenantId, typed.roles)

        _ <- ZIO.fail(Outcome.Forbidden)
          .unless(allowed.contains(endpoint.id))
      yield typed

    private def checkAudience(
        resource: Resource,
        claims: AccessTokenClaims,
    ): IO[Outcome, Unit] =
      val expectedAudience =
        resource.secret match
          case Some(_) => s"resource://${resource.resourceId}"
          case None => resource.resource.encode
      val allowed = claims.audience.contains(expectedAudience) ||
        resource.secret.isDefined && claims.audience.contains(edgeAudience)
      ZIO.fail(Outcome.Forbidden)
        .unless(allowed)
        .unit

    /** RFC 9470 §2: verify the token satisfies every authentication requirement of the
      * endpoint. Runs after `checkRules` so that a request the client is not authorized to
      * make at all is rejected with a definitive 403 rather than a misleading step-up
      * challenge. All requirements are evaluated together so a single challenge lists every
      * unmet one, letting the client fix them in one re-authentication round.
      *
      * ACR requirement:
      * - if `stepUpCondition` is absent or evaluates to false, no ACR is required.
      * - if `stepUpCondition` evaluates to true (including the literal `"true"`), `stepUpAcr` is required.
      * - to enforce ACR unconditionally, set `stepUpCondition` to `"true"`.
      *
      * `maxAge` is checked independently: if set, the token's `auth_time` claim must be present
      * and no older than `maxAge` seconds. This applies regardless of whether `stepUpCondition`
      * or `stepUpAcr` are configured.
      */
    private def checkStepUp(
        endpoint: ResourceEndpoint,
        claims: AccessTokenClaims,
        now: Instant,
        celContext: Map[String, AnyRef],
    ): IO[Outcome, Unit] =
      def unsatisfied(required: String): Option[String] =
        val requiredSet = required.split(' ').toSet.filter(_.nonEmpty)
        Option.unless(requiredSet.isEmpty || claims.acr.exists(requiredSet.contains))(required)

      val maxAgeFailed = endpoint.maxAge.exists { maxAge =>
        !claims.authTime.exists(authTime => now.getEpochSecond - authTime <= maxAge)
      }
      for
        conditionPassed <- endpoint.stepUpCondition match
          case None => ZIO.succeed(false)
          case Some(expression) =>
            celEvaluator.compile(expression)
              .flatMap(_.evaluateBoolean(celContext))

        requiredAcr = Option.when(conditionPassed)(endpoint.stepUpAcr).flatten
        acrFailed = requiredAcr.flatMap(unsatisfied)

        _ <- ZIO.fail(Outcome.InsufficientAuthentication(
          acr = acrFailed,
          maxAge = Option.when(maxAgeFailed)(endpoint.maxAge).flatten,
        )).when(acrFailed.isDefined || maxAgeFailed)
      yield ()

    private def checkRules(
        claims: Json.Obj,
        userInfo: Json.Obj,
        request: Request,
        endpoint: ResourceEndpoint,
        restPath: Path,
        parsedBody: Option[Json],
    ): IO[Outcome, Map[String, AnyRef]] =
      val context = buildCelContext(claims, userInfo, request, endpoint, restPath, parsedBody)
      ZIO.foreachDiscard(endpoint.allow.filter(_.trim.nonEmpty)): expression =>
        celEvaluator.compile(expression)
          .flatMap(_.evaluateBoolean(context))
          .flatMap(allowed => ZIO.fail(Outcome.Forbidden).unless(allowed))
      .as(context)

    private def refreshSession(
        accessTokenId: AccessTokenId,
        cookiePresetId: PresetId,
        now: Instant,
    ): IO[Throwable | Outcome, ActiveSession] =
      // When the session record, its preset or its refresh token is gone the
      // session is fully expired; fall back to the preset carried by the cookie so
      // the caller is sent to the right app's login, whichever app this edge is fronting.
      for
        record <- sessionRepository.findByAccessTokenId(accessTokenId)
          .someOrElseZIO(failWithReauthenticate(cookiePresetId, None, None))
        preset <- clientService.findPreset(record.presetId)
          .someOrElseZIO(failWithReauthenticate(cookiePresetId, None, None))
        encryptedRefreshToken <- ZIO.succeed(record.encryptedRefreshToken)
          .someOrElseZIO(failWithReauthenticate(record.presetId, preset.cookieDomain, preset.cookiePath))
        decryptedRefreshToken <- decryptRefreshToken(encryptedRefreshToken)
        session <- rotate(decryptedRefreshToken, preset, record.presetId)
      yield session

    private def rotate(
        refreshToken: RefreshToken,
        preset: AuthorizationPreset,
        presetId: PresetId,
    ): IO[Throwable | Outcome, ActiveSession] =
      val refreshed =
        for
          _ <- Observability.setPreviousRefreshToken(refreshToken)
          client <- clientService.findClient(preset.clientId).someOrFail(ClientNotFound(preset.clientId))
          tokens <- ssoClient.exchangeRefreshToken(refreshToken, client.id, client.secret)
          cookieTtl = Duration.fromSeconds(tokens.refreshTokenExpiresIn.getOrElse(tokens.expiresIn))
          _ <- storeSession(tokens, presetId, cookieTtl)
          publicKeys <- jwksService.getPublicKeys
          claims <- JWT.deserialize[Json.Obj](tokens.accessToken, publicKeys, JWT.Type.AccessToken)
            .orElseFail(Outcome.Unauthorized)
          now <- Clock.instant
          cookie = EdgeSessionCookie(
            presetId = presetId,
            accessToken = tokens.accessToken,
            ttl = cookieTtl,
            domain = preset.cookieDomain,
            path = preset.cookiePath,
            now = now,
          )
        yield ActiveSession(tokens.accessToken, claims, Some(cookie))

      refreshed.catchAll:
        case SSOClient.InvalidGrant =>
          failWithReauthenticate(preset.id, preset.cookieDomain, preset.cookiePath)
        case outcome: Outcome => ZIO.fail(outcome)
        case ex: Throwable => ZIO.fail(ex)

    // Signals the UI to re-authenticate: a 401 carrying a relative Location to
    // the app's own /login/<presetId> (a top-level navigation the SPA performs).
    // The login record is created only when the browser actually hits /login,
    // not on every failed background refresh.
    private def failWithReauthenticate(
        presetId: PresetId,
        cookieDomain: Option[String],
        cookiePath: Option[String],
    ): IO[Throwable | Outcome, Nothing] =
      ZIO.fail(
        Outcome.Reauthenticate(
          loginUri = URL(Path.root / "login" / presetId),
          clearCookie = EdgeSessionCookie.clear(cookieDomain, cookiePath),
        ),
      )

    private def buildCelContext(
        claims: Json.Obj,
        userInfo: Json.Obj,
        request: Request,
        endpoint: ResourceEndpoint,
        restPath: Path,
        parsedBody: Option[Json],
    ): Map[String, AnyRef] =
      val queryMap = request.url.queryParams.map.collect:
        case (k, values) if values.nonEmpty => k -> values.head
      val queriesMap = request.url.queryParams.map.collect:
        case (k, values) if values.nonEmpty => k -> values.toVector.asJava
      val headerMap = request.headers.iterator.map(h => h.headerName -> h.renderedValue).toMap
      val headersMap = request.headers.iterator.toVector.groupBy(_.headerName).map:
        case (k, values) => k -> values.map(_.renderedValue).asJava

      val pathParams = extractPathParams(endpoint.path, restPath)
      val pathData = Map[String, AnyRef]("params" -> pathParams.asJava)
      val requestData = scala.collection.mutable.LinkedHashMap[String, AnyRef](
        "path" -> pathData.asJava,
        "query" -> queryMap.asJava,
        "queryAll" -> queriesMap.asJava,
        "headers" -> headerMap.asJava,
        "headersAll" -> headersMap.asJava,
      )
      parsedBody.foreach(body => requestData("body") = JsonJava.toJava(body))
      Map(
        "token" -> JsonJava.toJava(claims),
        "user" -> JsonJava.toJava(userInfo),
        "request" -> requestData.asJava,
      )

    /** Chooses the upstream `Authorization` header for a resource: internal resources
      * (those with a secret) are proxied with edge's own `Basic(resourceId, secret)`
      * so the resource never sees the caller's token; public resources (no secret)
        * get the caller's own access token forwarded as-is.
      */
    private def resolveAuthHeader(
        resource: Resource,
        accessToken: AccessToken,
    ): Header.Authorization =
      resource.secret match
        case Some(secret) =>
          Header.Authorization.Basic(resource.resourceId, Base64.urlEncode(secret))
        case None =>
          Header.Authorization.Bearer(accessToken)

    private def buildUpstreamRequest(
        resource: Resource,
        endpoint: ResourceEndpoint,
        restPath: Path,
        request: Request,
        parsedBody: Option[Json],
        accessToken: AccessToken,
        celContext: Map[String, AnyRef],
    ): IO[Throwable | Outcome, Request] =
      val grouped = endpoint.inject.groupBy(_.target)
      val headerInjects = grouped.getOrElse(InjectTarget.header, Vector.empty)
      val queryInjects = grouped.getOrElse(InjectTarget.query, Vector.empty)
      val bodyInjects = grouped.getOrElse(InjectTarget.body, Vector.empty)

      val baseUrl = resource.resource
        .addPath(restPath)
        .setQueryParams(request.url.queryParams)

      val forwardedCookies = request.cookies
        .filter(_.name != EdgeSessionCookie.name)
        .map(_.toRequest)

      val baseHeaders = request.headers
        .removeHeader(Header.Cookie)
        .removeHeader(Header.Host)
        .removeHeader(Header.Authorization)
        // The body may be reconstructed/transformed (tenant-check parsing, inject rules),
        // so the incoming Content-Length no longer matches. Drop it and let the client
        // recompute it from the outgoing Body.
        .removeHeader(Header.ContentLength)

      val headersWithCookies = NonEmptyChunk.fromChunk(forwardedCookies) match
        case Some(cookies) => baseHeaders.addHeader(Header.Cookie(cookies))
        case None => baseHeaders

      val authHeader = resolveAuthHeader(resource, accessToken)
      for
        injectedHeaders <- evaluateAll(headerInjects, celContext)
        injectedQuery <- evaluateAll(queryInjects, celContext)
        finalHeaders = injectedHeaders.foldLeft(headersWithCookies):
          case (acc, (name, value)) => acc.removeHeader(name).addHeader(name, value)
        .addHeader(authHeader)
        finalUrl = injectedQuery.foldLeft(baseUrl):
          case (acc, (name, value)) => acc.removeQueryParam(name).addQueryParam(name, value)
        body <- applyBodyInjects(request, parsedBody, bodyInjects, celContext)
      yield request.copy(url = finalUrl, headers = finalHeaders, body = body)

    private def evaluateAll(
        rules: Vector[InjectRule],
        context: Map[String, AnyRef],
    ): IO[Throwable | Outcome, Vector[(String, String)]] =
      ZIO.foreach(rules): rule =>
        celEvaluator.compile(rule.expression)
          .flatMap(_.evaluateString(context))
          .map(_.map(rule.name -> _))
      .map(_.flatten)

    private def applyBodyInjects(
        request: Request,
        parsedBody: Option[Json],
        rules: Vector[InjectRule],
        context: Map[String, AnyRef],
    ): IO[Throwable | Outcome, Body] =
      parsedBody match
        case Some(obj: Json.Obj) if rules.nonEmpty =>
          evaluateAll(rules, context)
            .map(values => Json.Obj(Chunk.from(values.map((k, v) => (k, Json.Str(v))))))
            .map(json => Body.fromString(obj.merge(json).toJson, StandardCharsets.UTF_8))
        case Some(json) =>
          // Body was already read before permission check; reconstruct from the parsed JSON
          // so we don't double-consume the body stream when forwarding to upstream.
          ZIO.succeed(Body.fromString(json.toJson, StandardCharsets.UTF_8))
        case None =>
          ZIO.succeed(request.body)

    private def readJsonBody(request: Request): zio.Task[Option[Json]] =
      if !isJsonRequest(request) || request.body.isEmpty then ZIO.none
      else
        request.body.asString.flatMap: raw =>
          if raw.isEmpty then ZIO.none
          else ZIO.fromEither(raw.fromJson[Json]).mapError(new RuntimeException(_)).asSome

    private def isJsonRequest(request: Request): Boolean =
      request.header(Header.ContentType).exists(_.mediaType == MediaType.application.json)

    /** Picks the most specific endpoint whose template matches the request: a static
      * segment always wins over a `{name}` placeholder at the same position, so
      * `/users/me` takes precedence over `/users/{userId}` whatever the registration
      * order is. Templates that stay tied (they differ only in placeholder names, which
      * central rejects on registration) are ordered by path so the choice is stable.
      */
    private def findEndpoint(
        endpoints: Vector[ResourceEndpoint],
        method: String,
        restPath: Path,
    ): IO[Outcome, ResourceEndpoint] =
      ZIO.fromOption {
        val pathSegments = normalizePath(restPath.encode)
        endpoints
          .filter: endpoint =>
            endpoint.method.equalsIgnoreCase(method) && matchesSegments(normalizePath(endpoint.path), pathSegments)
          .minByOption(endpoint => (specificity(normalizePath(endpoint.path)), endpoint.path))
      }.orElseFail(Outcome.NotFound)

    /** Placeholder mask of a template, one character per segment: candidates all have the
      * same segment count, so comparing masks lexicographically prefers the template whose
      * leftmost differing segment is static. */
    private def specificity(pattern: Vector[String]): String =
      pattern.map:
        case s"{$_}" => '1'
        case _ => '0'
      .mkString

    private def matchesSegments(pattern: Vector[String], path: Vector[String]): Boolean =
      pattern.size == path.size && pattern.zip(path).forall:
        case (s"{$_}", _) => true
        case (a, b) => a == b

    private def extractPathParams(pattern: String, restPath: Path): Map[String, String] =
      normalizePath(pattern).zip(normalizePath(restPath.encode)).collect:
        case (s"{$name}", value) => name -> value
      .toMap

    private def normalizePath(path: String): Vector[String] =
      path.split('/').iterator.filter(_.nonEmpty).toVector

  private case class ActiveSession(
      accessToken: AccessToken,
      claims: Json.Obj,
      rotatedCookie: Option[Cookie.Response],
  )

  private enum Outcome:
    case Unauthorized
    case Forbidden
    case NotFound
    case InternalServerError
    case Reauthenticate(loginUri: URL, clearCookie: Cookie.Response)
    /** The token is valid but its authentication does not meet what this endpoint
      * requires (RFC 9470 §2): ACR too low (`acr`) and/or too old (`maxAge`). */
    case InsufficientAuthentication(acr: Option[String], maxAge: Option[Int])
