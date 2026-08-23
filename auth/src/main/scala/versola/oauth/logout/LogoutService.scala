package versola.oauth.logout

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord}
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionId, SessionInfo, SessionRecord}
import versola.user.model.UserId
import versola.util.CoreConfig
import zio.*
import zio.http.URL
import zio.json.ast.Json

trait LogoutService:
  def logout(
      identifier: Either[PublicSessionId, SessionId],
      postLogoutRedirectUri: Option[URL],
      state: Option[String],
  ): Task[LogoutService.LogoutResult]

  /** Admin-panel force-logout: invalidates every active session of a user and notifies,
    * via back-channel logout, every client that participated in each one. There is no
    * browser to redirect here, so unlike [[logout]] this produces no front-channel URIs. */
  def invalidateAllSessions(userId: UserId): Task[Unit]

object LogoutService:
  case class LogoutResult(
      logoutUris: List[URL],
      postLogoutRedirectUri: Option[URL],
      state: Option[String],
  )

  private val BackChannelLogoutEvent = "http://schemas.openid.net/event/backchannel-logout"

  val live = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      sessionService: SessionService,
      configuration: OAuthConfigurationService,
      config: CoreConfig,
      dispatcher: BackChannelDispatcher,
  ) extends LogoutService:

    override def logout(
        identifier: Either[PublicSessionId, SessionId],
        postLogoutRedirectUri: Option[URL],
        state: Option[String],
    ): Task[LogoutResult] =
      for
        session <- sessionService.invalidate(identifier)

        result <- session match
          // No session means there is no client to validate the redirect against; drop it
          // rather than passing it through unvalidated, which would be an open redirect.
          case None =>
            ZIO.succeed(LogoutResult(Nil, None, state))
          case Some(session) =>
            for
              sessionParticipants <- sessionClients(session.record.clients.map(_.clientId))
              logoutUris = sessionParticipants.flatMap(frontChannelLogoutUri(_, session.record.publicId)).distinct
              redirect <- postLogoutRedirectUri match
                case None      => ZIO.none
                case Some(uri) => ZIO.succeed(uri).whenZIO(allowedRedirect(sessionParticipants, uri))
              _ <- sendBackChannelLogouts(sessionParticipants, session.record)
            yield LogoutResult(logoutUris, redirect, state)
      yield result

    /** Ending every session a user has is one event per endpoint rather than one per session:
      * a logout token carrying a `sub` and no `sid` asks the RP to end all of that user's
      * sessions (OIDC Back-Channel Logout §2.4), so a user with five sessions across two
      * clients costs one or two deliveries instead of ten — and the RP records one revocation
      * instead of five.
      */
    override def invalidateAllSessions(userId: UserId): Task[Unit] =
      for
        sessions <- sessionService.invalidateAllByUser(userId)
        participants <- sessionClients(sessions.flatMap(_.clients.map(_.clientId)).distinct)
        _ <- ZIO.foreachDiscard(byEndpoint(participants)):
          case (uri, audience) => sendUserLogout(uri, audience, userId).forkDaemon
      yield ()

    /** Resolves the RPs that actually participated in this SSO session (tracked via
      * `SessionRecord.clients`, populated at issuance and on silent re-authorization),
      * rather than every client registered under the tenant. */
    private def sessionClients(clientIds: List[ClientId]): Task[List[OAuthClientRecord]] =
      ZIO.foreach(clientIds)(configuration.find).map(_.flatten)

    // TODO: `frontChannelLogoutSessionRequired` is currently ignored and session parameters are
    // always sent; the field is kept in the model/API for a future per-client toggle.
    private def frontChannelLogoutUri(client: OAuthClientRecord, sessionId: PublicSessionId): Option[URL] =
      client.frontChannelLogoutUri
        .map: uri =>
          uri.addQueryParams(List(
            "iss" -> config.jwt.issuer,
            "sid" -> sessionId,
          ))

    /** Guards against open redirects per OIDC RP-Initiated Logout §2: the target must
      * exactly match one of the `post_logout_redirect_uris` registered for the tenant
      * of a client participating in the session (unlike authorization redirect URIs,
      * origin-matching is not sufficient here). */
    private def allowedRedirect(clients: List[OAuthClientRecord], target: URL): UIO[Boolean] =
      clients.headOption match
        case None =>
          ZIO.succeed(false)
        case Some(client) =>
          configuration.getPostLogoutRedirectUris(client.tenantId)
            .map(_.contains(target))

    /** Groups the clients that registered a back-channel endpoint by that endpoint, dropping
      * those that registered none.
      *
      * Clients sharing an endpoint are one delivery, not several: every client behind the
      * same edge registers that edge's URI, and the edge would receive the same event once
      * per client and act on it once. Which clients it covers travels in the token's `aud`.
      */
    private def byEndpoint(clients: List[OAuthClientRecord]): List[(URL, NonEmptyChunk[ClientId])] =
      clients
        .flatMap(client => client.backChannelLogoutUri.map(_ -> client.id))
        .groupMap(_._1)(_._2)
        .toList
        .flatMap((uri, ids) => NonEmptyChunk.fromIterableOption(ids).map(uri -> _))

    /** OIDC Back-Channel Logout (spec §2.4): each endpoint is notified on its own daemon
      * fiber, so a slow or unreachable RP never delays or fails the user's own logout
      * response. */
    private def sendBackChannelLogouts(clients: List[OAuthClientRecord], session: SessionRecord): UIO[Unit] =
      ZIO.foreachDiscard(byEndpoint(clients)):
        case (uri, audience) => sendBackChannelLogout(uri, audience, session).forkDaemon

    private def sendBackChannelLogout(uri: URL, audience: NonEmptyChunk[ClientId], session: SessionRecord): UIO[Unit] =
      send(
        uri,
        audience,
        session.userId.toString,
        Json.Obj(
          "sid" -> Json.Str(session.publicId),
          "events" -> Json.Obj(BackChannelLogoutEvent -> Json.Obj()),
        ),
      )

    private def sendUserLogout(uri: URL, audience: NonEmptyChunk[ClientId], userId: UserId): UIO[Unit] =
      send(
        uri,
        audience,
        userId.toString,
        Json.Obj("events" -> Json.Obj(BackChannelLogoutEvent -> Json.Obj())),
      )

    private def send(uri: URL, audience: NonEmptyChunk[ClientId], subject: String, customClaims: Json.Obj): UIO[Unit] =
      dispatcher
        .dispatch(audience = audience, uri = uri, subject = subject, customClaims = customClaims)
        .catchAllCause(cause => ZIO.logWarningCause(s"Back-channel logout to '$uri' failed", cause))
