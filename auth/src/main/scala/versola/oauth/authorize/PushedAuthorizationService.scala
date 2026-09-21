package versola.oauth.authorize

import versola.oauth.authorize.model.{PushedAuthorizationError, PushedAuthorizationRecord, PushedAuthorizationResponse}
import versola.oauth.client.model.ClientCredentials
import versola.oauth.model.{RequestUri, RequestUriReference}
import versola.oauth.clientauth.{AuthenticatedEndpoint, ClientAuthentication}
import versola.oauth.mtls.ClientCertificate
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
      certificate: Option[ClientCertificate],
      request: Request,
  ): IO[Throwable | PushedAuthorizationError, PushedAuthorizationResponse]

object PushedAuthorizationService:
  def live: ZLayer[
    CoreConfig & AuthorizeRequestParser & PushedAuthorizationRepository & ClientAuthentication & RequestObjectService &
      SecureRandom & SecurityService,
    Nothing,
    PushedAuthorizationService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  /** RFC 9126 §7.1 defers to JAR §10.2(d), which requires at least 128 bits of entropy. */
  private val ReferenceLength = 32

  /** Client authentication parameters are not part of the authorization request (RFC 9126 §2.1),
    * so they are dropped before the request is validated and persisted.
    */
  private val CredentialParams = Set("client_secret")

  class Impl(
      config: CoreConfig,
      parser: AuthorizeRequestParser,
      repository: PushedAuthorizationRepository,
      clientAuthentication: ClientAuthentication,
      requestObjectService: RequestObjectService,
      secureRandom: SecureRandom,
      securityService: SecurityService,
  ) extends PushedAuthorizationService:

    override def push(
        params: Map[String, Chunk[String]],
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
        request: Request,
    ): IO[Throwable | PushedAuthorizationError, PushedAuthorizationResponse] =
      for
        // RFC 8705 §2.1 / RFC 7523 §2.2: a client that registered a certificate subject or a
        // key set authenticates with that, not with the secret it also holds.
        client <- clientAuthentication.authenticate(
          credentials = credentials,
          certificate = certificate,
          endpoint = AuthenticatedEndpoint.PushedAuthorizationRequest,
        ).mapError {
          case error: Throwable => error
          case _ => PushedAuthorizationError.InvalidClient
        }

        _ <- ZIO.fail(PushedAuthorizationError.RequestUriNotAllowed).when(params.contains("request_uri"))
        // A client_id that contradicts the authenticated one is already rejected while the
        // credentials are extracted, so only its absence is left to check here.
        _ <- ZIO.fail(PushedAuthorizationError.ClientIdMissing).unless(params.contains("client_id"))

        // RFC 9126 §3: a pushed request may itself be a JAR request object. It is resolved
        // here rather than at redemption so that the object is verified while the client that
        // signed it is authenticated, and so that what is stored is the request it stated --
        // by the time the user finishes logging in, the object's own `exp` may have passed.
        authorizationParams <- requestObjectService.resolve(params -- CredentialParams)
          .mapError(PushedAuthorizationError.from)
        _ <- parser.validate(authorizationParams, request).mapError(PushedAuthorizationError.from)

        reference <- secureRandom.nextBytes(ReferenceLength).map(RequestUriReference(_))
        referenceMac <- securityService.mac(Secret(reference), config.security.parRequestsSecret)
        ttl = config.parOrDefault.requestUriTtl
        record = PushedAuthorizationRecord(client.id, authorizationParams.view.mapValues(_.toList).toMap)
        _ <- repository.create(referenceMac, record, ttl)
      yield PushedAuthorizationResponse(RequestUri(reference), ttl.toSeconds)
