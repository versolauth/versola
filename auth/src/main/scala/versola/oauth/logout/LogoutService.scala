package versola.oauth.logout

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord}
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionId, SessionInfo}
import versola.util.{CoreConfig, MAC}
import zio.*
import zio.http.{Scheme, URL}

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

  val live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      sessionService: SessionService,
      configuration: OAuthConfigurationService,
      config: CoreConfig,
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
              clients <- tenantClients(session.record.clientId)
              logoutUris = clients.flatMap(frontChannelLogoutUri(_, session.record.publicId)).distinct
              redirect = postLogoutRedirectUri.filter(allowedRedirect(clients, _))
            yield LogoutResult(logoutUris, redirect, state)
      yield result

    private def tenantClients(clientId: ClientId): Task[List[OAuthClientRecord]] =
      configuration.find(clientId).flatMap:
        case Some(client) => configuration.findByTenant(client.tenantId).map(_.toList)
        case None         => ZIO.succeed(Nil)

    private def frontChannelLogoutUri(client: OAuthClientRecord, sessionId: PublicSessionId): Option[URL] =
      client.frontChannelLogoutUri
        .flatMap(URL.decode(_).toOption)
        .map: uri =>
          if client.frontChannelLogoutSessionRequired then
            uri.addQueryParams(List(
              "iss" -> config.jwt.issuer,
              "sid" -> sessionId,
            ))
          else uri

    /** Guards against open redirects: the target must share an origin with a redirect URI
      * registered by one of the clients participating in the session. `hostPort` alone
      * ignores the scheme, so scheme is compared separately to prevent downgrading an
      * `https` redirect URI to `http` on the same host. */
    private def allowedRedirect(clients: List[OAuthClientRecord], target: URL): Boolean =
      def origin(url: URL): Option[(Scheme, String)] =
        url.hostPort.map((url.scheme.getOrElse(Scheme.HTTPS), _))

      val allowedOrigins = clients
        .flatMap(_.redirectUris.toSet)
        .flatMap(URL.decode(_).toOption)
        .flatMap(origin)
        .toSet
      origin(target).exists(allowedOrigins.contains)
