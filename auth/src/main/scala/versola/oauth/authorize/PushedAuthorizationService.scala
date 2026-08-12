package versola.oauth.authorize

import versola.oauth.authorize.model.{PushedAuthorizationError, PushedAuthorizationRecord, PushedAuthorizationResponse}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientIdWithSecret}
import versola.oauth.model.{RequestUri, RequestUriReference}
import versola.util.{CoreConfig, Secret, SecureRandom, SecurityService}
import zio.http.Request
import zio.{Chunk, IO, ZIO, ZLayer}

/**
 * OAuth 2.0 Pushed Authorization Requests
 * RFC 9126: https://datatracker.ietf.org/doc/html/rfc9126
 */
trait PushedAuthorizationService:
  def push(
      params: Map[String, Chunk[String]],
      credentials: ClientCredentials,
      request: Request,
  ): IO[Throwable | PushedAuthorizationError, PushedAuthorizationResponse]

object PushedAuthorizationService:
  def live: ZLayer[
    CoreConfig & AuthorizeRequestParser & PushedAuthorizationRepository & OAuthConfigurationService & SecureRandom &
      SecurityService,
    Nothing,
    PushedAuthorizationService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  /** RFC 9126 §7.1 defers to JAR §10.2(d), which requires at least 128 bits of entropy. */
  private val ReferenceLength = 32

  class Impl(
      config: CoreConfig,
      parser: AuthorizeRequestParser,
      repository: PushedAuthorizationRepository,
      oauthClientService: OAuthConfigurationService,
      secureRandom: SecureRandom,
      securityService: SecurityService,
  ) extends PushedAuthorizationService:

    override def push(
        params: Map[String, Chunk[String]],
        credentials: ClientCredentials,
        request: Request,
    ): IO[Throwable | PushedAuthorizationError, PushedAuthorizationResponse] =
      for
        client <- credentials match
          case ClientIdWithSecret(clientId, clientSecret) =>
            oauthClientService.verifySecret(clientId, clientSecret)
              .someOrFail(PushedAuthorizationError.InvalidClient)

        _ <- ZIO.fail(PushedAuthorizationError.RequestUriNotAllowed).when(params.contains("request_uri"))
        // A client_id that contradicts the authenticated one is already rejected while the
        // credentials are extracted, so only its absence is left to check here.
        _ <- ZIO.fail(PushedAuthorizationError.ClientIdMissing).unless(params.contains("client_id"))

        _ <- parser.validate(params, request).mapError(PushedAuthorizationError.from)

        reference <- secureRandom.nextBytes(ReferenceLength).map(RequestUriReference(_))
        referenceMac <- securityService.mac(Secret(reference), config.security.parRequestsSecret)
        ttl = config.parOrDefault.requestUriTtl
        record = PushedAuthorizationRecord(client.id, params.view.mapValues(_.toList).toMap)
        _ <- repository.create(referenceMac, record, ttl)
      yield PushedAuthorizationResponse(RequestUri(reference), ttl.toSeconds)
