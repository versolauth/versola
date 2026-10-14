package versola.edge

import versola.edge.dpop.DpopVerifier
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
  InvalidLogoutToken,
  PermissionId,
  PresetId,
  PresetNotFound,
  RefreshToken,
  RefreshTokenFamilyId,
  Resource,
  ResourceEndpoint,
  ResourceEndpointId,
  ResourceId,
  RoleId,
  SessionId,
  State,
  TenantId,
  TokenResponse,
}
import versola.edge.revocation.{RevocationKey, TokenRevocationService}
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
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.{MapHasAsJava, SeqHasAsJava}

trait EdgeService:
  def authorize(
      presetId: PresetId,
      overrideParams: Map[String, String] = Map.empty,
  ): IO[Throwable | PresetNotFound, URL]

  /** Like [[authorize]], for the preset of the caller's own live session (`GET /login/current`).
    * The EDGE_SESSION cookie is caller-supplied, and its preset id is not authenticated, so the
    * preset is taken from the edge session record the cookie's access token maps to, never from
    * the cookie text. A missing, forged, lapsed, revoked or mismatched session is reported as
    * [[PresetNotFound]], the same answer an unknown preset gets, so the route reveals nothing.
    */
  def authorizeForSession(
      sessionCookie: Option[String],
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

  /** Front-channel logout, either OP-initiated (`iss`/`sid` query params, rendered in an
    * iframe) or first-party (the browser navigating to the endpoint itself). The presented
    * EDGE_SESSION cookie is the only credential that authorizes a revocation: `sid` alone
    * is attacker-supplied, so it clears cookies but never writes to the deny list. When
    * `iss` doesn't match the configured OP the request is silently ignored rather than
    * failing.
    */
  def frontChannelLogout(
      iss: Option[String],
      sid: Option[SessionId],
      sessionCookie: Option[String],
  ): Task[List[Cookie.Response]]

  /** OP-initiated back-channel logout: validates the OP-signed `logout_token` and revokes
    * the SSO session it names. Server-to-server, so no cookie can be cleared; the
    * revocation is what stops the EDGE_SESSION cookie from being honoured on the next
    * request.
    */
  def backChannelLogout(logoutToken: String): IO[Throwable | InvalidLogoutToken, Unit]

object EdgeService:
  /** The preset id edge's `GET /login/current` route owns; central refuses to create it. */
  val ReservedPresetId: PresetId = PresetId("current")

  /** The exchange with auth outlasted the refresh lease's budget; see `refreshExchangeTimeout`. */
  final class RefreshExchangeTimedOut extends RuntimeException("refresh token exchange with auth timed out")

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

  /** Auth's own event for revoked access tokens, named either one `jti` at a time or by the
    * refresh-token family they came from. Deliberately not the OIDC logout event: that one
    * ends the whole session for every client sharing it, which is not what one client
    * revoking one of its tokens may trigger.
    */
  private val AccessTokenRevocationEvent = "versola:event:access-token-revocation"

  private val DpopScheme = DpopVerifier.Scheme

  /** The claims of a security event token the OP pushes to this edge — an OIDC Back-Channel
    * Logout token (spec §2.4) or an access token revocation. Back-Channel Logout requires a
    * `sub`, a `sid` or both, and reads a token without a `sid` as covering every session of
    * that subject, which is what an administrator ending a user's access sends.
    * `revoked_jti`/`revoked_fam`/`revoked_exp` describe the token(s) a revocation names; they
    * are deliberately not `jti`/`exp`, which belong to the event token itself. `revoked_jti`
    * and `revoked_fam` are always JSON arrays, even when either names one thing, so there is
    * one shape to decode rather than a singular-or-array ambiguity; everything they name
    * shares the one `revoked_exp` bound.
    */
  private case class LogoutTokenClaims(
      @jsonField("iss") issuer: String,
      @jsonField("aud") audience: List[ClientId],
      @jsonField("sub") subject: Option[String],
      @jsonField("sid") sessionId: Option[SessionId],
      @jsonField("iat") issuedAt: Long,
      /** When the event happened, as against `iat`, when the token announcing it was signed
        * (RFC 8417 §2.2). Optional: an OP that does not send it leaves `iat` as the closest
        * thing to it available.
        */
      @jsonField("toe") timeOfEvent: Option[Long],
      @jsonField("revoked_jti") revokedTokenIds: Option[List[AccessTokenId]],
      @jsonField("revoked_fam") revokedFamilies: Option[List[RefreshTokenFamilyId]],
      @jsonField("revoked_exp") revokedTokenExpiresAt: Option[Long],
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
    OAuthClientService & ResourceService & CelEvaluator & SecureRandom & LoginRepository & SSOClient & SecurityService & Client & EdgeConfig & session.EdgeSessionRepository & TokenRevocationService & JwksService & PermissionService & DpopVerifier & EnvName,
    Nothing,
    EdgeService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _))

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
      revocationService: TokenRevocationService,
      jwksService: JwksService,
      permissionService: PermissionService,
      dpopVerifier: DpopVerifier,
      env: EnvName,
  ) extends EdgeService:

    private val loginTtl = 10.minutes
    private val encryptionKey = SecretKeySpec(config.security.tokenEncryption.key, "AES")
    private val edgeAudience = "resource://edge"

    /** Extra time the edge_sessions row is kept alive past the cookie/token expiry,
      * so the session record is never deleted before the cookie has actually expired.
      */
    private val sessionExpiryGracePeriod = 10.seconds

    // How long a request may hold the right to refresh one session. It has to outlast the round trip
    // to auth; if the holder dies the lease simply runs out. Requests that lose the claim wait up to
    // this long for the holder's result.
    private val refreshLeaseDuration = 10.seconds
    private val refreshPollInterval = 100.millis
    // Waiters look for the holder's result every poll but only retry the claim every few polls, so a
    // burst of requests on one session costs about one query per poll each instead of two.
    private val claimRetryEveryPolls = 5L
    // The exchange with auth is abandoned before the lease runs out. Otherwise a slow auth would let
    // a waiter claim the lease while the holder's request is still in flight and present the same
    // refresh token a second time, which revokes the family.
    private val refreshExchangeTimeout = 8.seconds

    // How long after a rotation a cookie of the previous generation is still served the current access
    // token. It only has to cover requests already in flight with the old cookie and the browser's
    // propagation of the new one. Bounding it matters: without it a stolen old cookie would keep
    // working until the next rotation, since nothing else invalidates it. Auth does not do this for
    // us, it refuses the old refresh token at once.
    private val rotationGracePeriod = 10.seconds
    // The floor for the cookie handed to a request that adopts another's token, so a row about to
    // lapse does not produce a cookie that expires before the browser stores it.
    private val minAdoptedCookieTtl = 1.second

    override def authorize(
        presetId: PresetId,
        overrideParams: Map[String, String] = Map.empty,
    ): IO[Throwable | PresetNotFound, URL] =
      clientService.findPreset(presetId)
        .someOrElseZIO(Observability.setError("preset_not_found") *> ZIO.fail(PresetNotFound()))
        .flatMap(prepareAuthorizeUrl(_, overrideParams))

    override def authorizeForSession(
        sessionCookie: Option[String],
        overrideParams: Map[String, String],
    ): IO[Throwable | PresetNotFound, URL] =
      for
        content <- ZIO.fromOption(sessionCookie).orElse(rejectSession("session_missing"))
        (cookiePresetId, token) = EdgeSessionCookie.parse(content)
        publicKeys <- jwksService.getPublicKeys
        now <- Clock.instant
        // The signature is checked before expiry, so an expired token is still one this edge's
        // OP signed. Its session may well be alive: the access token is far shorter-lived than
        // the cookie, and the proxy refreshes it from the same session record.
        verified <- JWT.deserialize[Json.Obj](token, publicKeys, JWT.Type.AccessToken)
          .map(claims => (jtiOf(claims), claims.as[AccessTokenClaims].toOption))
          .catchAll:
            case JWT.Error.Expired(jti) => ZIO.succeed((Some(jti), None))
            case _ => rejectSession("session_invalid")
        (jti, typedClaims) = verified
        record <- ZIO.fromOption(jti).orElse(rejectSession("session_invalid"))
          .flatMap(id => sessionRepository.findByAccessTokenId(AccessTokenId(id)))
          .someOrElseZIO(rejectSession("session_unknown"))
        // The cookie's own preset id must agree with the record: it is only a hint until then.
        _ <- rejectSession("session_unknown").when(record.presetId != cookiePresetId || !record.expiresAt.isAfter(now))
        // `current` names this route (central refuses to create such a preset); never resolve it as one.
        _ <- rejectSession("session_preset_reserved").when(record.presetId == EdgeService.ReservedPresetId)
        revoked <- ZIO.foreach(typedClaims): claims =>
          revocationService.isRevoked(
            RevocationKey.of(claims.jti, claims.family, claims.sid, claims.subject),
            Instant.ofEpochSecond(claims.issuedAt),
          )
        _ <- rejectSession("session_revoked").when(revoked.contains(true))
        url <- authorize(record.presetId, overrideParams)
      yield url

    private def jtiOf(claims: Json.Obj): Option[String] =
      claims.fields.collectFirst { case ("jti", Json.Str(jti)) => jti }

    private def rejectSession(code: String): IO[PresetNotFound, Nothing] =
      Observability.setError(code) *> ZIO.fail(PresetNotFound())

    private def prepareAuthorizeUrl(preset: AuthorizationPreset, overrideParams: Map[String, String]): zio.Task[URL] =
      for
        // The client, not just the preset: what the authorization request may look like is
        // the client's registration (RFC 9101 §10.5, RFC 9126 §6.2), and the credential that
        // signs or pushes it hangs off the same record.
        client <- clientService.findClient(preset.clientId).someOrFail(ClientNotFound(preset.clientId))

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

        authUrl <- ssoClient.authorizeUri(preset, client, codeChallenge, state, overrideParams)
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
          credential = client.credential,
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
        encryptedAccessToken <- encryptAccessToken(tokens.accessToken)
        _ <- sessionRepository.create(
          EdgeSessionRecord(
            publicSessionId = ids.sessionId,
            presetId = presetId,
            accessTokenId = ids.accessTokenId,
            encryptedRefreshToken = encryptedRefreshToken,
            expiresAt = now.plusSeconds(cookieTtl.toSeconds + sessionExpiryGracePeriod.toSeconds),
            previousAccessTokenId = None,
            encryptedAccessToken = Some(encryptedAccessToken),
            rotatedAt = None,
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

    // Stored so that a request which lost a refresh race can be handed the winner's token. It is
    // sealed like the refresh token: the row then reveals no more than it did before.
    private def encryptAccessToken(accessToken: AccessToken): Task[Secret] =
      securityService.encryptAes256(
        accessToken.getBytes(StandardCharsets.UTF_8),
        encryptionKey,
      ).map(Secret(_))

    private def decryptAccessToken(encrypted: Secret): Task[AccessToken] =
      securityService.decryptAes256(encrypted, encryptionKey)
        .map(bytes => AccessToken(new String(bytes, StandardCharsets.UTF_8)))

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
        case Outcome.InvalidDpopProof =>
          ZIO.succeed(
            Response.status(Status.Unauthorized)
              .addHeader(Header.Custom("WWW-Authenticate", """DPoP error="invalid_dpop_proof"""")),
          )
        // §9: the nonce goes in its own header, and the challenge tells the client why to
        // look for it. The client is expected to retry once with a proof made over it.
        case Outcome.UseDpopNonce(nonce) =>
          ZIO.succeed(
            Response.status(Status.Unauthorized)
              .addHeader(Header.Custom("WWW-Authenticate", """DPoP error="use_dpop_nonce""""))
              .addHeader(Header.Custom("DPoP-Nonce", nonce)),
          )
        case ex: Throwable => ZIO.fail(ex)

    override def getMyPermissions(
        claims: PermissionsClaims,
        resourceIds: List[ResourceId],
    ): UIO[PermissionsResponse] =
      val roles = claims.roles.getOrElse(List.empty)

      ZIO.foreach(resourceIds): resourceId =>
        for
          resource <- resourceService.findByResourceId(resourceId)
          endpointIds = resource.fold(Set.empty[ResourceEndpointId])(_.endpoints.map(_.id).toSet)
          // A token without a tenant claim cannot match a role grant, which is keyed by tenant.
          perms <- ZIO.foreach(claims.tenantId)(permissionService.getPermissionsForRoles(_, roles, endpointIds))
        yield Some(resourceId -> ResourcePermissions(perms.getOrElse(Set.empty)))
      .map(entries => PermissionsResponse(entries.flatten.toMap, env.isProd))

    override def frontChannelLogout(
        iss: Option[String],
        sid: Option[SessionId],
        sessionCookie: Option[String],
    ): Task[List[Cookie.Response]] =
      if !iss.forall(value => URL.decode(value).toOption.exists(sameOrigin(_, config.versolaUrl))) then
        ZIO.succeed(Nil)
      else
        for
          cookieSid <- ZIO.foreach(sessionCookie)(verifiedSessionId).map(_.flatten)
          // Only a caller holding a session token for this sid may record a revocation. A
          // bare `sid` still clears cookies, but an unauthenticated caller cannot make an
          // edge deny tokens it was never shown.
          revoked = (sid, cookieSid) match
            case (Some(claimed), Some(held)) if claimed == held => Some(held)
            case (None, held) => held
            case _ => None
          // The lookup here is for the cookies, not the revocation: this is the browser-facing
          // half of a logout and it has to name the exact domain and path to clear.
          records <- ZIO.foreach(sid.orElse(cookieSid))(sessionRepository.findBySessionId).map(_.getOrElse(Nil))
          presets <- clientService.listPresets(records.map(_.presetId).distinct)
          _ <- ZIO.foreachDiscard(revoked)(revocationService.revokeSession)
        yield presets.map(preset => EdgeSessionCookie.clear(preset.cookieDomain, preset.cookiePath))

    /** The sid of the access token carried in EDGE_SESSION, or None when the cookie is
      * absent, unparseable, forged or expired. The signature check is what makes this a
      * credential: the cookie is caller-supplied, so unverified claims prove nothing.
      */
    private def verifiedSessionId(content: String): Task[Option[SessionId]] =
      val (_, token) = EdgeSessionCookie.parse(content)
      for
        publicKeys <- jwksService.getPublicKeys
        claims <- JWT.deserialize[Json.Obj](token, publicKeys, JWT.Type.AccessToken).option
      yield claims.flatMap(_.fields.collectFirst { case ("sid", Json.Str(value)) => SessionId(value) })

    override def backChannelLogout(logoutToken: String): IO[Throwable | InvalidLogoutToken, Unit] =
      for
        publicKeys <- jwksService.getPublicKeys
        claims <- JWT.deserialize[EdgeService.LogoutTokenClaims](logoutToken, publicKeys, JWT.Type.JWT)
          .mapError(error => InvalidLogoutToken(s"logout token is not a valid JWT: $error"))
        _ <- validateLogoutToken(claims)
        _ <-
          if claims.events.contains(EdgeService.AccessTokenRevocationEvent) then revokeToken(claims)
          else endSession(claims)
      yield ()

    /** A logout naming a session ends that session; one naming only a subject ends every
      * session that user has here, which is how an administrator revokes their access.
      *
      * One revocation is the whole of what a logout does here, and it touches no table on
      * the way. The `edge_sessions` rows are left alone: deleting them would stop the
      * EDGE_SESSION cookie being honoured, but not the access tokens already issued under
      * the session — which stay signed and unexpired, and which the proxy accepts as bearer
      * tokens without consulting those rows at all. The revocation covers both, so the rows
      * are left to expire on their own. A refresh cannot revive one either, since the session
      * behind it is gone at auth.
      */
    private def endSession(claims: EdgeService.LogoutTokenClaims): IO[Throwable | InvalidLogoutToken, Unit] =
      (claims.sessionId, claims.subject) match
        case (Some(sid), _) => revocationService.revokeSession(sid)
        // `toe` in preference to `iat`: the bound has to be when the administrator acted,
        // not when the token telling us about it was signed. A delivery that took a moment
        // to run would otherwise put the boundary after a login the user made in between,
        // and lock them out of it for an access token's lifetime.
        case (None, Some(subject)) =>
          revocationService.revokeUser(subject, Instant.ofEpochSecond(claims.timeOfEvent.getOrElse(claims.issuedAt)))
        case (None, None) => ZIO.fail(InvalidLogoutToken("logout token carries neither a sid nor a sub claim"))

    /** An event names the tokens it revokes either individually or by the refresh chain that
      * issued them. Both readings are accepted, and an event carrying both is read as naming
      * everything either one does -- neither is a superset of the other, and an event is one
      * revocation however many keys it took to express it, so it is written as one.
      */
    private def revokeToken(claims: EdgeService.LogoutTokenClaims): IO[Throwable | InvalidLogoutToken, Unit] =
      val named =
        claims.revokedTokenIds.getOrElse(Nil).map(RevocationKey.Jti(_)) :::
          claims.revokedFamilies.getOrElse(Nil).map(RevocationKey.Fam(_))
      for
        keys <- ZIO.fromOption(NonEmptyChunk.fromIterableOption(named))
          .orElseFail(InvalidLogoutToken("access token revocation carries no revoked_jti or revoked_fam claim"))
        expiresAt <- ZIO.fromOption(claims.revokedTokenExpiresAt)
          .orElseFail(InvalidLogoutToken("access token revocation carries no revoked_exp claim"))
        _ <- revocationService.revokeTokens(keys, Instant.ofEpochSecond(expiresAt))
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
          .unless(
            claims.events.contains(EdgeService.BackChannelLogoutEvent) ||
              claims.events.contains(EdgeService.AccessTokenRevocationEvent),
          )
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
        started <- Clock.nanoTime
        (accessToken, authSource) <- extractAccessToken(request)
        publicKeys <- jwksService.getPublicKeys
        now <- Clock.instant
        session <- JWT.deserialize[Json.Obj](accessToken, publicKeys, JWT.Type.AccessToken)
          .foldZIO(
            {
              case JWT.Error.Expired(jti) =>
                authSource match
                  case AuthSource.Cookie(presetId) => refreshSession(AccessTokenId(jti), presetId, now)
                  case AuthSource.Header(_) =>
                    Observability.setError("token_expired") *> ZIO.fail(Outcome.Unauthorized)
              case _ =>
                Observability.setError("token_invalid") *> ZIO.fail(Outcome.Unauthorized)
            },
            claims => ZIO.succeed(ActiveSession(accessToken, claims, None)),
          )
        typedClaims <- ZIO.fromEither(session.claims.as[AccessTokenClaims]).orElseFail(Outcome.Unauthorized)
        _ <- logAccessTokenClaims(typedClaims)
        _ <- checkRevoked(typedClaims)
        _ <- checkTokenBinding(request, session.accessToken, typedClaims, authSource)

        resource <- resourceService.findByResourceId(resourceId).someOrFail(Outcome.NotFound)
        endpoint <- findEndpoint(resource.endpoints, request.method.name, restPath)
        // the registered template, not the concrete path: a parameterized endpoint would
        // otherwise produce one metric time series per parameter value
        _ <- Observability.setRoutePath(s"/resources/$resourceId${endpoint.path}")
        parsedBody <- readJsonBody(request)
        _ <- checkPermissions(typedClaims, endpoint)
        _ <- checkAudience(resource, typedClaims)
        userInfo <- fetchUserInfo(session.accessToken, typedClaims)
          .when(endpoint.fetchUserInfo)
          .someOrElse(Json.Obj())
          .mapError {
            case SSOClient.UserInfoUnauthorized => Outcome.Unauthorized
            case _: Throwable => Outcome.InternalServerError
          }
          .tapError {
            case Outcome.Unauthorized => Observability.setError("user_info_unauthorized")
            case _ => ZIO.unit
          }
        celContext <- checkRules(session.claims, userInfo, request, endpoint, restPath, parsedBody)
        _ <- checkStepUp(endpoint, typedClaims, now, celContext)
        upstream <- buildUpstreamRequest(resource, endpoint, restPath, request, parsedBody, session.accessToken, celContext)
        // `collect` inside the scope: the response this returns is handed straight back to
        // edge's own server, which streams it to the caller long after the upstream scope has
        // closed. Left streaming, whatever the body had not yet delivered by then dies with the
        // upstream connection, and the caller sees its own connection close mid-body.
        dispatched <- Clock.nanoTime
        _ <- ProxyMetrics.beforeUpstream(resourceId, dispatched - started)
        response <- ZIO.scoped(httpClient.request(upstream).flatMap(_.collect))
        stripped = response.removeHeader(Header.SetCookie)
      yield session.rotatedCookie.fold(stripped)(stripped.addCookie)

    private enum AuthSource:
      case Cookie(presetId: PresetId)
      case Header(scheme: AuthScheme)

    /** Which RFC 9449 §7.1 / RFC 6750 scheme the caller presented its token under. The token
      * itself is the same either way; the scheme decides whether a proof is demanded. */
    private enum AuthScheme:
      case Bearer
      case Dpop

    private def extractAccessToken(request: Request): IO[Outcome, (AccessToken, AuthSource)] =
      ZIO.fromOption(
        request.header(Header.Authorization)
          .collect {
            case Header.Authorization.Bearer(token) =>
              (AccessToken(token.stringValue), AuthSource.Header(AuthScheme.Bearer))
            // zio-http has no `DPoP` case, so the scheme arrives unparsed with the token as
            // its parameters. RFC 9110 §11.1 makes scheme matching case-insensitive.
            case Header.Authorization.Unparsed(scheme, token) if scheme.equalsIgnoreCase(DpopScheme) =>
              (AccessToken(token.stringValue), AuthSource.Header(AuthScheme.Dpop))
          }
          .orElse(
            request.cookie(EdgeSessionCookie.name).map { cookie =>
              val (presetId, token) = EdgeSessionCookie.parse(cookie.content)
              (token, AuthSource.Cookie(presetId))
            },
          ),
      ).orElseFail(Outcome.Unauthorized)
        .tapError(_ => Observability.setError("token_missing"))

    /** Annotates the request's `session_id`/`user_id`/`token` as soon as the access token's
      * claims are known, so every subsequent log line (including one from a rejected
      * permission check) carries them.
      */
    private def logAccessTokenClaims(claims: AccessTokenClaims): UIO[Unit] =
      Observability.setToken(claims.jti) *>
        Observability.setUserId(claims.subject) *>
        ZIO.foreachDiscard(claims.sid)(Observability.setSessionId)

    /** RFC 9449 §7.1 and RFC 8705 §3: decides what the presented token obliges the caller to
      * prove, and holds the request until it has.
      *
      * The `cnf` claim, not the scheme, is what makes a proof mandatory. A caller choosing
      * `Bearer` for a key-bound token is exactly the downgrade §7.2 exists to refuse, so that
      * refusal is unconditional -- it holds whether or not this edge has a `dpop` block
      * configured, and for a token arriving in the session cookie too.
      *
      * A certificate-bound token (`cnf.x5t#S256`) is answered differently, because the
      * certificate it names is this edge's own: edge is the RFC 8705 client that obtained the
      * token, and no caller of edge holds that certificate or could present it here. Such a
      * token is honoured only out of the `EDGE_SESSION` cookie -- where edge put it itself,
      * sealed, and got it back. Presented in an `Authorization` header it is refused: edge
      * terminates no TLS of its own (there is no `ChallengeSettings` sync and no certificate
      * parsing on this side -- #320), so it cannot tell a holder of a leaked bound token from
      * the client the token was bound to, and §3 is explicit that a bound token must not be
      * honoured for anyone else.
      */
    private def checkTokenBinding(
        request: Request,
        accessToken: AccessToken,
        claims: AccessTokenClaims,
        authSource: AuthSource,
    ): IO[Outcome, Unit] =
      val presentation = authSource match
        case AuthSource.Cookie(_) => Presentation.Cookie
        case AuthSource.Header(AuthScheme.Bearer) => Presentation.Bearer
        case AuthSource.Header(AuthScheme.Dpop) => Presentation.Dpop
      TokenBinding.check(claims.confirmation, presentation) match
        case BindingCheck.CertificateOutsideSession =>
          Observability.setError("certificate_bound_token_outside_session") *>
            ZIO.fail(Outcome.Unauthorized)

        case BindingCheck.VerifyProof(jkt) =>
          val verified =
            for
              proof <- DpopVerifier.proofHeader(request)
              _ <- dpopVerifier.verify(
                proofHeader = proof,
                accessToken = accessToken,
                boundKeyThumbprint = jkt,
                method = request.method,
                // The path the client addressed, not the rest-path forwarded upstream: the
                // proof was signed over the URI the client called this edge on.
                path = request.url.path,
              )
            yield ()
          verified.catchAll:
            case DpopVerifier.Error.NonceRequired(nonce) =>
              Observability.setError("dpop_nonce_required") *> ZIO.fail(Outcome.UseDpopNonce(nonce))
            // Named down to which check the proof failed: every one of these answers the
            // same 401, so the metric is the only thing that tells them apart.
            case DpopVerifier.Error.InvalidProof(reason) =>
              Observability.setError(s"dpop_proof_${reason.productPrefix.toLowerCase}") *>
                ZIO.fail(Outcome.InvalidDpopProof)
            case error =>
              Observability.setError(s"dpop_${error.productPrefix.toLowerCase}") *>
                ZIO.fail(Outcome.InvalidDpopProof)

        case BindingCheck.Downgrade =>
          Observability.setError("dpop_downgrade") *> ZIO.fail(Outcome.InvalidDpopProof)

        // A proof signed with some key says nothing about a token that was never bound to
        // one: anyone holding the token could have produced it. §7.1 has the resource server
        // treat the token as invalid rather than fall back to bearer semantics.
        case BindingCheck.UnboundDpop =>
          Observability.setError("dpop_unbound_token") *> ZIO.fail(Outcome.Unauthorized)

        case BindingCheck.Accept => ZIO.unit

    /** Auth enforces a token's binding on `/userinfo` as strictly as the proxy enforces it
      * here, so the call has to carry whatever that binding names -- and the two are carried
      * in different places: an edge assertion in a header for a key-bound token, the
      * certificate itself in the handshake for a certificate-bound one. Only the second needs
      * the client looked up: its certificate is part of the registration central syncs, not
      * of the token.
      */
    private def fetchUserInfo(
        accessToken: AccessToken,
        claims: AccessTokenClaims,
    ): IO[Throwable | SSOClient.UserInfoUnauthorized.type, Json.Obj] =
      for
        binding <-
          (claims.confirmation.flatMap(_.jkt), claims.confirmation.flatMap(_.certificateThumbprint)) match
            case (Some(_), _) => ZIO.succeed(SSOClient.TokenBinding.Key)
            case (None, Some(_)) =>
              clientService.findClient(claims.clientId)
                .someOrFail(ClientNotFound(claims.clientId))
                .map(client => SSOClient.TokenBinding.Certificate(client.id, client.credential))
            case (None, None) => ZIO.succeed(SSOClient.TokenBinding.Unbound)
        info <- ssoClient.userInfo(accessToken, binding)
      yield info

    /** A signed, unexpired token is not necessarily still valid: it may have been revoked on
      * its own, or its whole session logged out. Both are answered from memory, so this costs
      * no I/O on the hot path.
      */
    private def checkRevoked(claims: AccessTokenClaims): IO[Outcome, Unit] =
      revocationService
        .isRevoked(
          RevocationKey.of(claims.jti, claims.family, claims.sid, claims.subject),
          Instant.ofEpochSecond(claims.issuedAt),
        )
        .flatMap(revoked => ZIO.fail(Outcome.Unauthorized).when(revoked))
        .unit

    private def checkPermissions(
        claims: AccessTokenClaims,
        endpoint: ResourceEndpoint,
    ): IO[Outcome, Unit] =
      val isServiceToken = claims.subject == claims.clientId
      for
        allowed <-
          if isServiceToken then
            permissionService.getAllowedEndpointsForClient(claims.clientId)
          else
            permissionService.getAllowedEndpointsForRoles(claims.tenantId, claims.roles)

        _ <- (Observability.setError("permission_denied") *> ZIO.fail(Outcome.Forbidden))
          .unless(allowed.contains(endpoint.id))
      yield ()

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
      (Observability.setError("audience_denied") *> ZIO.fail(Outcome.Forbidden))
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
              .catchAll:
                // The condition decides whether the stronger authentication is required, so an
                // expression that can't say either way has to be read as "required": reading it
                // as "not required" would let a token that can't be checked through on the
                // weaker one it already has.
                case CelEvaluator.EvaluationError.DataMissing => ZIO.succeed(true)
                case CelEvaluator.EvaluationError.Broken =>
                  Observability.setError("step_up_condition_failed", Some("cel condition could not be evaluated")) *>
                    ZIO.fail(Outcome.InternalServerError)

        requiredAcr = Option.when(conditionPassed)(endpoint.stepUpAcr).flatten
        acrFailed = requiredAcr.flatMap(unsatisfied)

        _ <- (Observability.setError("step_up_required") *> ZIO.fail(Outcome.InsufficientAuthentication(
          acr = acrFailed,
          maxAge = Option.when(maxAgeFailed)(endpoint.maxAge).flatten,
        ))).when(acrFailed.isDefined || maxAgeFailed)
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
          .flatMap { allowed =>
            (Observability.setError("access_rule_denied") *> ZIO.fail(Outcome.Forbidden)).unless(allowed)
          }
          .catchAll:
            // A rule that reads a claim this token doesn't carry is unsatisfied, the same as one
            // that evaluates cleanly to false -- same code, so a denial is one thing to alert
            // on, with the description telling the two apart when someone looks.
            case CelEvaluator.EvaluationError.DataMissing =>
              Observability.setError("access_rule_denied", Some("cel rule read a value the request doesn't carry")) *>
                ZIO.fail(Outcome.Forbidden)
            case CelEvaluator.EvaluationError.Broken =>
              Observability.setError("access_rule_failed", Some("cel rule could not be evaluated")) *>
                ZIO.fail(Outcome.InternalServerError)
            case outcome: Outcome => ZIO.fail(outcome)
      .as(context)

    // Auth rotates refresh tokens strictly: presenting one a second time revokes the whole family
    // (OAuthTokenService.detectReplay), with no grace window. Two requests carrying the same expired
    // cookie must therefore not both refresh. The one that claims the row's lease does; the others
    // are handed the token it produces. The lease is a column and not a database lock because the
    // refresh is an HTTP call to auth, and no connection or transaction may stay open across it.
    private def refreshSession(
        accessTokenId: AccessTokenId,
        cookiePresetId: PresetId,
        now: Instant,
    ): IO[Throwable | Outcome, ActiveSession] =
      sessionRepository.claimRefresh(accessTokenId, now, now.plus(refreshLeaseDuration))
        .flatMap:
          case Some(record) => refreshClaimed(accessTokenId, record, cookiePresetId)
          case None => adoptRotation(accessTokenId, cookiePresetId)

    private def refreshClaimed(
        accessTokenId: AccessTokenId,
        record: EdgeSessionRecord,
        cookiePresetId: PresetId,
    ): IO[Throwable | Outcome, ActiveSession] =
      // When the session record, its preset or its refresh token is gone the
      // session is fully expired; fall back to the preset carried by the cookie so
      // the caller is sent to the right app's login, whichever app this edge is fronting.
      val refreshed =
        for
          preset <- clientService.findPreset(record.presetId)
            .someOrElseZIO(failWithReauthenticate(cookiePresetId, None, None))
          encryptedRefreshToken <- ZIO.succeed(record.encryptedRefreshToken)
            .someOrElseZIO(failWithReauthenticate(record.presetId, preset.cookieDomain, preset.cookiePath))
          decryptedRefreshToken <- decryptRefreshToken(encryptedRefreshToken).catchSome:
            // GCM authenticates as it decrypts, so a token sealed under another EDGE_TOKEN_ENC_KEY
            // (lost or replaced key) or altered in the store fails the tag check; a value shorter
            // than IV + tag is rejected before that. Both mean this row can never be read again:
            // send the caller to log in, which overwrites the row. Any other failure of the
            // decryption itself -- a key that is not an AES key, say -- is not the session's fault
            // and stays a failure.
            // No separate log line: the error code on the HTTP log context is enough.
            case _: AEADBadTagException | _: IllegalArgumentException =>
              Observability.setError("refresh_token_undecryptable") *>
                failWithReauthenticate(record.presetId, preset.cookieDomain, preset.cookiePath)
          session <- rotate(decryptedRefreshToken, preset, record.presetId)
        yield session

      // A successful rotation ends the lease itself (the upsert in storeSession). Anything else must
      // hand it back, or the requests waiting on this one sit out the whole lease. A failed release
      // is ignored so it cannot replace the error that got us here; the lease then just expires.
      // A timed-out exchange is the exception: auth may still have spent the token, so the lease is
      // kept and runs out on its own rather than letting a waiter present the token at once.
      refreshed.onError: cause =>
        if cause.failureOption.exists(_.isInstanceOf[RefreshExchangeTimedOut]) then ZIO.unit
        else sessionRepository.releaseRefresh(accessTokenId).ignore

    /** Reached when this request did not get the lease: either another request is refreshing the same
      * session right now, or it already did and this request carries the cookie from before. In both
      * cases the result is the other request's access token, so auth is not called.
      */
    private def adoptRotation(
        accessTokenId: AccessTokenId,
        cookiePresetId: PresetId,
    ): IO[Throwable | Outcome, ActiveSession] =
      findRotated(accessTokenId).flatMap:
        case Some(rotated) => sessionFromRotated(rotated, cookiePresetId)
        case None =>
          sessionRepository.findByAccessTokenId(accessTokenId).flatMap:
            case Some(_) => awaitRotation(accessTokenId, cookiePresetId, refreshLeaseDuration.toMillis / refreshPollInterval.toMillis)
            case None =>
              // No row under the old id. Either the session is gone, or the holder committed after
              // the read above and before this one; reading again tells the two apart.
              findRotated(accessTokenId).flatMap:
                case Some(rotated) => sessionFromRotated(rotated, cookiePresetId)
                case None => failWithReauthenticate(cookiePresetId, None, None)

    // The row this request's stale cookie lost to, if the rotation is recent enough to honour it (see
    // rotationGracePeriod). Rotation time is stamped when the holder stores its result, so a waiter
    // that sees the result is always inside the window.
    private def findRotated(accessTokenId: AccessTokenId): Task[Option[EdgeSessionRecord]] =
      Clock.instant.flatMap(now =>
        sessionRepository.findByPreviousAccessTokenId(accessTokenId, now.minus(rotationGracePeriod)),
      )

    // Polls instead of subscribing to anything: edge replicas share nothing but the database. Each
    // round also retries the claim, so a holder that failed (and released) or died (lease expired)
    // does not leave its waiters to time out; one of them takes over and refreshes.
    private def awaitRotation(
        accessTokenId: AccessTokenId,
        cookiePresetId: PresetId,
        remaining: Long,
    ): IO[Throwable | Outcome, ActiveSession] =
      if remaining <= 0 then reauthenticateFromCookie(cookiePresetId)
      else
        for
          _ <- ZIO.sleep(refreshPollInterval)
          rotated <- findRotated(accessTokenId)
          session <- rotated match
            case Some(record) => sessionFromRotated(record, cookiePresetId)
            case None if remaining % claimRetryEveryPolls != 0 =>
              awaitRotation(accessTokenId, cookiePresetId, remaining - 1)
            case None =>
              for
                now <- Clock.instant
                claimed <- sessionRepository.claimRefresh(accessTokenId, now, now.plus(refreshLeaseDuration))
                session <- claimed match
                  case Some(record) => refreshClaimed(accessTokenId, record, cookiePresetId)
                  case None => awaitRotation(accessTokenId, cookiePresetId, remaining - 1)
              yield session
        yield session

    /** Serves the access token another request obtained by refreshing this session, validated again
      * here. The cookie lifetime is what is left of the row's, so it ends when the winner's does.
      */
    private def sessionFromRotated(
        rotated: EdgeSessionRecord,
        cookiePresetId: PresetId,
    ): IO[Throwable | Outcome, ActiveSession] =
      for
        preset <- clientService.findPreset(rotated.presetId)
          .someOrElseZIO(failWithReauthenticate(cookiePresetId, None, None))
        reauthenticate = failWithReauthenticate(rotated.presetId, preset.cookieDomain, preset.cookiePath)
        encryptedAccessToken <- ZIO.succeed(rotated.encryptedAccessToken).someOrElseZIO(reauthenticate)
        // An unreadable value is handled as for the refresh token: this row cannot serve the caller.
        accessToken <- decryptAccessToken(encryptedAccessToken).catchSome:
          case _: AEADBadTagException | _: IllegalArgumentException =>
            Observability.setError("access_token_undecryptable") *> reauthenticate
        publicKeys <- jwksService.getPublicKeys
        // Expired or malformed: the winner's token is no better than the one the caller came with.
        claims <- JWT.deserialize[Json.Obj](accessToken, publicKeys, JWT.Type.AccessToken)
          .orElse(reauthenticate)
        now <- Clock.instant
        remaining = Duration.fromSeconds(rotated.expiresAt.getEpochSecond - sessionExpiryGracePeriod.toSeconds - now.getEpochSecond)
        cookie = EdgeSessionCookie(
          presetId = rotated.presetId,
          accessToken = accessToken,
          ttl = if remaining.compareTo(minAdoptedCookieTtl) < 0 then minAdoptedCookieTtl else remaining,
          domain = preset.cookieDomain,
          path = preset.cookiePath,
          now = now,
        )
      yield ActiveSession(accessToken, claims, Some(cookie))

    // Waiting for the holder timed out. The preset is looked up for the cookie's domain and path, so
    // that clearing it reaches the cookie that was set.
    private def reauthenticateFromCookie(cookiePresetId: PresetId): IO[Throwable | Outcome, Nothing] =
      clientService.findPreset(cookiePresetId).flatMap: preset =>
        failWithReauthenticate(cookiePresetId, preset.flatMap(_.cookieDomain), preset.flatMap(_.cookiePath))

    private def rotate(
        refreshToken: RefreshToken,
        preset: AuthorizationPreset,
        presetId: PresetId,
    ): IO[Throwable | Outcome, ActiveSession] =
      val refreshed =
        for
          _ <- Observability.setPreviousRefreshToken(refreshToken)
          client <- clientService.findClient(preset.clientId).someOrFail(ClientNotFound(preset.clientId))
          tokens <- ssoClient.exchangeRefreshToken(refreshToken, client.id, client.credential)
            .timeoutFail(RefreshExchangeTimedOut())(refreshExchangeTimeout)
          cookieTtl = Duration.fromSeconds(tokens.refreshTokenExpiresIn.getOrElse(tokens.expiresIn))
          _ <- storeSession(tokens, presetId, cookieTtl)
          publicKeys <- jwksService.getPublicKeys
          claims <- JWT.deserialize[Json.Obj](tokens.accessToken, publicKeys, JWT.Type.AccessToken)
            .orElse(Observability.setError("token_invalid") *> ZIO.fail(Outcome.Unauthorized))
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
        // The proof is signed over this edge's own method and URI and is single-use here,
        // so it is meaningless upstream -- and upstream is not the audience that was asked
        // to hold it.
        .removeHeader(DpopScheme)
        // The body may be reconstructed/transformed (tenant-check parsing, inject rules),
        // so the incoming Content-Length no longer matches. Drop it and let the client
        // recompute it from the outgoing Body.
        .removeHeader(Header.ContentLength)

      val headersWithCookies = NonEmptyChunk.fromChunk(forwardedCookies) match
        case Some(cookies) => baseHeaders.addHeader(Header.Cookie(cookies))
        case None => baseHeaders

      val authHeader = resolveAuthHeader(resource, accessToken)
      val strippedQuery = queryInjects.foldLeft(baseUrl) { (acc, rule) =>
        acc.removeQueryParam(rule.name)
      }
      for
        injectedHeaders <- evaluateAll(headerInjects, celContext)
        injectedQuery <- evaluateAll(queryInjects, celContext)
        strippedInjectedHeaders = headerInjects.foldLeft(headersWithCookies) {
          case (acc, rule) => acc.removeHeader(rule.name)
        }
        finalHeaders = injectedHeaders.foldLeft(strippedInjectedHeaders) {
          case (acc, (name, value)) => acc.removeHeader(name).addHeader(name, value)
        }.addHeader(authHeader)
        finalUrl = injectedQuery.foldLeft(strippedQuery) {
          case (acc, (name, value)) => acc.removeQueryParam(name).addQueryParam(name, value)
        }
        body <- applyBodyInjects(request, parsedBody, bodyInjects, celContext)
      yield request.copy(url = finalUrl, headers = finalHeaders, body = body)

    /** An inject rule that produces nothing is not forwarded as a request with the value simply
      * missing: upstream has no way to tell an absent header from one it was never meant to
      * get, and it may well be the value upstream scopes its own authorization by. A rule that
      * is meant to be optional can evaluate to an empty string via `has(...)`. */
    private def evaluateAll(
        rules: Vector[InjectRule],
        context: Map[String, AnyRef],
    ): IO[Throwable | Outcome, Vector[(String, String)]] =
      ZIO.foreach(rules): rule =>
        celEvaluator.compile(rule.expression)
          .flatMap(_.evaluateString(context))
          .map(_.map(rule.name -> _))
          .catchAll:
            case CelEvaluator.EvaluationError.DataMissing =>
              Observability.setError("inject_rule_denied", Some("cel rule read a value the request doesn't carry")) *>
                ZIO.fail(Outcome.Forbidden)
            case CelEvaluator.EvaluationError.Broken =>
              Observability.setError("inject_rule_failed", Some("cel rule could not be evaluated")) *>
                ZIO.fail(Outcome.InternalServerError)
      .map(_.flatten)

    private def applyBodyInjects(
        request: Request,
        parsedBody: Option[Json],
        rules: Vector[InjectRule],
        context: Map[String, AnyRef],
    ): IO[Throwable | Outcome, Body] =
      parsedBody match
        case Some(obj: Json.Obj) if rules.nonEmpty =>
          val injectedNames = rules.map(_.name).toSet
          evaluateAll(rules, context)
            .map { values =>
              val fields = obj.fields.filterNot((name, _) => injectedNames.contains(name))
              Json.Obj(Chunk.fromIterable(fields ++ values.map((k, v) => (k, Json.Str(v)))))
            }
            .map(json => Body.fromString(json.toJson, StandardCharsets.UTF_8))
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

    /** RFC 9449 §7.1: the token is sender-constrained and the accompanying proof of that
      * key was absent, malformed, bound elsewhere, or already used. */
    case InvalidDpopProof

    /** RFC 9449 §9: the proof has to be re-made over a nonce this edge issued. */
    case UseDpopNonce(nonce: String)
