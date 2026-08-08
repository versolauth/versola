package versola.oauth.logout

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord}
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionId, SessionInfo, SessionRecord}
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.http.{Body, Client, Form, Request, URL}
import zio.json.ast.Json

trait LogoutService:
  def logout(
      identifier: Either[PublicSessionId, SessionId],
      postLogoutRedirectUri: Option[URL],
      state: Option[String],
  ): Task[LogoutService.LogoutResult]

object LogoutService:
  case class LogoutResult(
      logoutUris: List[URL],
      postLogoutRedirectUri: Option[URL],
      state: Option[String],
  )

  private val BackChannelLogoutEvent = "http://schemas.openid.net/event/backchannel-logout"

  /** How long a back-channel logout token issued to an RP is valid for. */
  private val TokenTtl = 2.minutes

  /** How long the OP waits for an RP's back-channel logout endpoint to respond before
    * giving up on that single, fire-and-forget delivery attempt (no retries). */
  private val RequestTimeout = 5.seconds

  val live = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      sessionService: SessionService,
      configuration: OAuthConfigurationService,
      config: CoreConfig,
      httpClient: Client,
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

    /** OIDC Back-Channel Logout (spec §2.4): each RP with a `backChannelLogoutUri` is
      * notified on its own daemon fiber, bounded by `backChannelLogout.requestTimeout`
      * and without retries, so a slow or unreachable RP never delays or fails the
      * user's own logout response. */
    private def sendBackChannelLogouts(clients: List[OAuthClientRecord], session: SessionRecord): UIO[Unit] =
      ZIO.foreachDiscard(clients)(sendBackChannelLogout(_, session).forkDaemon)

    private def sendBackChannelLogout(client: OAuthClientRecord, session: SessionRecord): UIO[Unit] =
      client.backChannelLogoutUri match
        case None => ZIO.unit
        case Some(uri) =>
          deliverLogoutToken(client, session, uri)
            .timeoutFail(RuntimeException(s"back-channel logout to client '${client.id}' timed out"))(
              RequestTimeout,
            )
            .catchAllCause(cause => ZIO.logWarningCause(s"Back-channel logout to client '${client.id}' failed", cause))

    private def deliverLogoutToken(client: OAuthClientRecord, session: SessionRecord, uri: URL): Task[Unit] =
      for
        token <- logoutToken(client, session)
        request = Request.post(uri, Body.fromURLEncodedForm(Form.fromStrings("logout_token" -> token)))
        response <- ZIO.scoped(httpClient.request(request))
        _ <- ZIO
          .fail(RuntimeException(s"back-channel logout endpoint responded with ${response.status.code}"))
          .unless(response.status.isSuccess)
      yield ()

    private def logoutToken(client: OAuthClientRecord, session: SessionRecord): Task[String] =
      for
        keyId <- config.jwt.requireKeyId
        token <- JWT.serialize(
          claims = JWT.Claims(
            issuer = config.jwt.issuer,
            subject = session.userId.toString,
            audience = List(client.id),
            custom = Json.Obj(
              "sid" -> Json.Str(session.publicId),
              "events" -> Json.Obj(BackChannelLogoutEvent -> Json.Obj()),
            ),
          ),
          ttl = TokenTtl,
          signature = JWT.Signature.Asymmetric(
            algorithm = JWT.Algorithm.RS256,
            keyId = keyId,
            privateKey = config.jwt.privateKey,
          ),
        )
      yield token
