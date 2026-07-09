package versola.edge

import versola.edge.login.{LoginRecord, LoginRepository}
import versola.edge.model.{
  AccessToken,
  AccessTokenClaims,
  AccessTokenId,
  AuthConversationNotFound,
  AuthorizationPreset,
  ClientId,
  Code,
  CodeVerifier,
  InjectRule,
  InjectTarget,
  PermissionId,
  PresetId,
  PresetNotFound,
  RefreshToken,
  Resource,
  ResourceEndpoint,
  ResourceEndpointId,
  ResourceId,
  RoleId,
  State,
  TenantId,
  TokenResponse,
}
import versola.edge.session.EdgeRefreshTokenRepository
import versola.util.cel.CelEvaluator
import versola.util.{Base64, Base64Url, JWT, JsonJava, RedirectUri, Secret, SecureRandom, SecurityService}
import zio.http.{Body, Client, Cookie, Header, MediaType, Path, Request, Response, Status, URL}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec}
import zio.{Chunk, Clock, Duration, IO, NonEmptyChunk, Task, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.{MapHasAsJava, SeqHasAsJava}

trait EdgeService:
  def authorize(
      presetId: PresetId,
  ): IO[Throwable | PresetNotFound, URL]

  def complete(
      code: Code,
      state: State,
  ): IO[Throwable | AuthConversationNotFound, EdgeService.LoginCompletion]

  def proxy(
      resourceId: ResourceId,
      restPath: Path,
      request: Request,
  ): Task[Response]

  def getMyPermissions(
      claims: PermissionsClaims,
      resourceIds: List[ResourceId],
  ): UIO[EdgeService.PermissionsResponse]

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

  case class ResourcePermissions(
      permissions: Set[PermissionId],
  ) derives JsonCodec

  case class PermissionsResponse(
      resources: Map[ResourceId, ResourcePermissions],
  ) derives JsonCodec

  def live: ZLayer[
    OAuthClientService & ResourceService & CelEvaluator & SecureRandom & LoginRepository & SSOClient & SecurityService & Client & EdgeConfig & session.EdgeRefreshTokenRepository & JwksService & PermissionService,
    Nothing,
    EdgeService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _))

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
      refreshTokenRepository: EdgeRefreshTokenRepository,
      jwksService: JwksService,
      permissionService: PermissionService,
  ) extends EdgeService:

    private val loginTtl = 10.minutes
    private val encryptionKey = SecretKeySpec(config.security.tokenEncryption.key, "AES")

    override def authorize(
        presetId: PresetId,
    ): IO[Throwable | PresetNotFound, URL] =
      clientService.findPreset(presetId).someOrFail(PresetNotFound()).flatMap(prepareAuthorizeUrl)

    private def prepareAuthorizeUrl(preset: AuthorizationPreset): zio.Task[URL] =
      for
        codeVerifier <- secureRandom.nextBytes(32).map(CodeVerifier.fromBytes)

        codeChallenge = Base64.urlEncode:
          MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.getBytes(StandardCharsets.UTF_8))

        state <- secureRandom.nextBytes(16).map(State.fromBytes)
        loginId <- secureRandom.nextBytes(32).map(Base64.urlEncode)

        _ <- loginRepository.create(
          loginId = loginId,
          record = LoginRecord(
            codeVerifier = codeVerifier,
            presetId = preset.id,
            state = state,
          ),
          ttl = loginTtl,
        )

        authUrl <- ssoClient.authorizeUri(preset, codeChallenge, state)
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
        _ <- storeRefreshToken(tokens, preset.id, cookieTtl)
        _ <- loginRepository.deleteByState(state)
      yield LoginCompletion(
        presetId = preset.id,
        accessToken = tokens.accessToken,
        cookieTtl = cookieTtl,
        postLoginRedirectUri = preset.postLoginRedirectUri,
        cookieDomain = preset.cookieDomain,
        cookiePath = preset.cookiePath,
      )

    private def storeRefreshToken(
        tokens: TokenResponse,
        presetId: PresetId,
        refreshTokenTtl: Duration,
    ): zio.Task[Unit] =
      tokens.refreshToken match
        case Some(refreshToken) =>
          for
            now <- Clock.instant
            accessTokenId <- extractAccessTokenId(tokens.accessToken)
            expiresAt = now.plusSeconds(refreshTokenTtl.toSeconds)
            encryptedRefreshToken <- encryptRefreshToken(refreshToken)
            _ <- refreshTokenRepository.create(
              accessTokenId,
              session.EdgeRefreshTokenRecord(
                presetId = presetId,
                encryptedRefreshToken = encryptedRefreshToken,
                expiresAt = expiresAt,
              ),
            )
          yield ()
        case None =>
          ZIO.unit

    private def encryptRefreshToken(refreshToken: RefreshToken): Task[Secret] =
      securityService.encryptAes256(
        refreshToken.getBytes(StandardCharsets.UTF_8),
        encryptionKey,
      ).map(Secret(_))

    private def decryptRefreshToken(encrypted: Secret): Task[RefreshToken] =
      securityService.decryptAes256(encrypted, encryptionKey)
        .map(bytes => RefreshToken(new String(bytes, StandardCharsets.UTF_8)))

    private def extractAccessTokenId(accessToken: AccessToken): Task[AccessTokenId] =
      jwksService.getPublicKeys
        .flatMap { keys =>
          JWT.deserialize[Json.Obj](accessToken, keys, JWT.Type.AccessToken)
            .flatMap { claims =>
              ZIO.fromOption(claims.get("jti").collect { case Json.Str(s) => AccessTokenId(s) })
                .orElseFail(new RuntimeException("jti claim missing from JWT"))
            }
        }
        .mapError(e => new RuntimeException(s"Failed to validate JWT: $e"))

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
        case ex: Throwable => ZIO.fail(ex)

    private val centralResourceId = ResourceId("central")

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
      .map(entries => PermissionsResponse(entries.flatten.toMap))

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

        resource <- resourceService.findByResourceId(resourceId).someOrFail(Outcome.NotFound)
        endpoint <- findEndpoint(resource.endpoints, request.method.name, restPath)
        parsedBody <- readJsonBody(request)
        typedClaims <- checkPermissions(resourceId, session.claims, endpoint, request, parsedBody)
        userInfo <- ssoClient.userInfo(session.accessToken)
          .when(endpoint.fetchUserInfo)
          .someOrElse(Json.Obj())
          .mapError {
            case SSOClient.UserInfoUnauthorized => Outcome.Unauthorized
            case _: Throwable => Outcome.InternalServerError
          }
        celContext <- checkRules(session.claims, userInfo, request, endpoint, restPath, parsedBody)
        upstream <- buildUpstreamRequest(resource, endpoint, restPath, request, parsedBody, typedClaims.clientId, celContext)
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

    private def checkPermissions(
        resourceId: ResourceId,
        claims: Json.Obj,
        endpoint: ResourceEndpoint,
        request: Request,
        parsedBody: Option[Json],
    ): IO[Outcome, AccessTokenClaims] =
      for
        typed <- ZIO.fromEither(claims.as[AccessTokenClaims]).orElseFail(Outcome.Unauthorized)
        isCentral = resourceId == centralResourceId
        isServiceToken = typed.subject == typed.clientId

        allowed <-
          if isServiceToken then
            permissionService.getAllowedEndpointsForClient(typed.clientId)
          else if isCentral then
            permissionService.getAllowedEndpointsForRoles(Map(TenantId.default -> typed.roles))
          else
            val rolesMap = typed.tenantId.fold(Map.empty[TenantId, List[RoleId]])(tid => Map(tid -> typed.roles))
            permissionService.getAllowedEndpointsForRoles(rolesMap)

        _ <- ZIO.fail(Outcome.Forbidden)
          .unless(allowed.contains(endpoint.id))
      yield typed

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
      // When the refresh record or its preset is gone the session is fully
      // expired; fall back to the preset carried by the cookie so the caller is
      // sent to the right app's login, whichever app this edge is fronting.
      for
        record <- refreshTokenRepository.find(accessTokenId)
          .someOrElseZIO(failWithReauthenticate(cookiePresetId, None, None))
        preset <- clientService.findPreset(record.presetId)
          .someOrElseZIO(failWithReauthenticate(cookiePresetId, None, None))
        decryptedRefreshToken <- decryptRefreshToken(record.encryptedRefreshToken)
        session <- rotate(decryptedRefreshToken, preset, record.presetId)
      yield session

    private def rotate(
        refreshToken: RefreshToken,
        preset: AuthorizationPreset,
        presetId: PresetId,
    ): IO[Throwable | Outcome, ActiveSession] =
      val refreshed =
        for
          client <- clientService.findClient(preset.clientId).someOrFail(ClientNotFound(preset.clientId))
          tokens <- ssoClient.exchangeRefreshToken(refreshToken, client.id, client.secret)
          cookieTtl = Duration.fromSeconds(tokens.refreshTokenExpiresIn.getOrElse(tokens.expiresIn))
          _ <- storeRefreshToken(tokens, presetId, cookieTtl)
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
      Clock.instant.flatMap { now =>
        ZIO.fail(
          Outcome.Reauthenticate(
            loginUri = URL(Path.root / "login" / presetId),
            clearCookie = EdgeSessionCookie.clear(cookieDomain, cookiePath, now),
          ),
        )
      }

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
      val requestData = scala.collection.mutable.LinkedHashMap[String, AnyRef](
        "path" -> pathParams.asJava,
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

    private def buildUpstreamRequest(
        resource: Resource,
        endpoint: ResourceEndpoint,
        restPath: Path,
        request: Request,
        parsedBody: Option[Json],
        clientId: ClientId,
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

      for
        authHeader <- clientService.findClient(clientId)
          .someOrFail(Outcome.InternalServerError: Throwable | Outcome)
          .map(client => Header.Authorization.Basic(client.id, Base64.urlEncode(client.secret)))
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

    private def findEndpoint(
        endpoints: Vector[ResourceEndpoint],
        method: String,
        restPath: Path,
    ): IO[Outcome, ResourceEndpoint] =
      ZIO.fromOption {
        val pathSegments = normalizePath(restPath.encode)
        endpoints.find: endpoint =>
          endpoint.method.equalsIgnoreCase(method) && matchesSegments(normalizePath(endpoint.path), pathSegments)
      }.orElseFail(Outcome.NotFound)

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
