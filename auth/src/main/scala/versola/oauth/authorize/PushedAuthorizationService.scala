package versola.oauth.authorize

import versola.oauth.authorize.model.{PushedAuthorizationError, PushedAuthorizationRecord, PushedAuthorizationResponse}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientCredentials, ClientId}
import versola.oauth.dpop.DpopService
import versola.oauth.clientauth.{AuthenticatedEndpoint, ClientAuthentication}
import versola.oauth.model.{RequestUri, RequestUriReference}
import versola.oauth.mtls.ClientCertificate
import versola.util.{CoreConfig, Dpop, RequestObject, Secret, SecureRandom, SecurityService}
import zio.http.{Method, Request}
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
      dpopProofs: List[String],
  ): IO[Throwable | PushedAuthorizationError, PushedAuthorizationResponse]

object PushedAuthorizationService:
  def live: ZLayer[
    CoreConfig & AuthorizeRequestParser & PushedAuthorizationRepository & ClientAuthentication & RequestObjectService &
      SecureRandom & SecurityService & DpopService & OAuthConfigurationService,
    Nothing,
    PushedAuthorizationService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _))

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
      dpopService: DpopService,
      configuration: OAuthConfigurationService,
  ) extends PushedAuthorizationService:

    override def push(
        params: Map[String, Chunk[String]],
        credentials: ClientCredentials,
        certificate: Option[ClientCertificate],
        request: Request,
        dpopProofs: List[String],
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

        // RFC 9449 §10.1: a proof sent here binds the authorization code to its key, which is
        // what `dpop_jkt` states outright -- so the proof is turned into that parameter, and
        // `/authorize` and the token endpoint then need to know of nothing else.
        dpopJkt <- verifyDpopProof(client.id, dpopProofs, request)
        boundParams <- bindDpopKey(params, dpopJkt)

        _ <- ZIO.fail(PushedAuthorizationError.RequestUriNotAllowed).when(params.contains("request_uri"))
        // A client_id that contradicts the authenticated one is already rejected while the
        // credentials are extracted, so only its absence is left to check here.
        _ <- ZIO.fail(PushedAuthorizationError.ClientIdMissing).unless(params.contains("client_id"))

        // RFC 9101 §10.5: a client that registered the requirement states its request in a
        // signed object here too -- `/authorize` reads a pushed request as already signed, so
        // an unsigned one stored now would reach it having never been checked.
        _ <- ZIO.fail(PushedAuthorizationError.RequestObjectRequired)
          .when(client.requireSignedRequestObject && !params.contains(RequestObject.Parameter))

        // RFC 9126 §3: a pushed request may itself be a JAR request object. It is resolved
        // here rather than at redemption so that the object is verified while the client that
        // signed it is authenticated, and so that what is stored is the request it stated --
        // by the time the user finishes logging in, the object's own `exp` may have passed.
        authorizationParams <- requestObjectService.resolve(boundParams -- CredentialParams)
          .mapError(PushedAuthorizationError.from)
        _ <- parser.validate(authorizationParams, request).mapError(PushedAuthorizationError.from)

        reference <- secureRandom.nextBytes(ReferenceLength).map(RequestUriReference(_))
        referenceMac <- securityService.mac(Secret(reference), config.security.parRequestsSecret)
        ttl = config.parOrDefault.requestUriTtl
        record = PushedAuthorizationRecord(client.id, authorizationParams.view.mapValues(_.toList).toMap)
        _ <- repository.create(referenceMac, record, ttl)
      yield PushedAuthorizationResponse(RequestUri(reference), ttl.toSeconds)

    /** The key the proof is signed with, if the request carries one. The endpoint is held to
      * the same policy as the token endpoint -- the client's registered narrowing of the
      * algorithms, and the tenant's nonce setting -- because this is the other place a token's
      * key is chosen.
      */
    private def verifyDpopProof(
        clientId: ClientId,
        proofs: List[String],
        request: Request,
    ): IO[Throwable | PushedAuthorizationError, Option[String]] =
      proofs match
        case Nil => ZIO.none
        case proof :: Nil =>
          for
            requireNonce <- configuration.requireDpopNonce(clientId)
            keyPolicy <- configuration.getDpopKeyPolicy(clientId)
            verified <- dpopService.verify(
              token = proof,
              method = Method.POST,
              uri = config.endpointUri(request.remoteCertificate.isDefined, "/par"),
              requireNonce = requireNonce,
              keyPolicy = keyPolicy,
            ).mapError {
              case DpopService.Error.InvalidProof(reason) => PushedAuthorizationError.InvalidDpopProof(reason.toString)
              case DpopService.Error.Replayed => PushedAuthorizationError.InvalidDpopProof("proof has already been used")
              case DpopService.Error.NonceRequired(nonce) => PushedAuthorizationError.UseDpopNonce(nonce)
              case error: Throwable => error
            }
          yield Some(verified.jkt)
        case _ =>
          ZIO.fail(PushedAuthorizationError.InvalidDpopProof("request must contain exactly one DPoP header"))

    /** RFC 9449 §10.1: a `dpop_jkt` that names another key than the proof's is a request to
      * bind the code to a key its sender cannot prove to hold.
      */
    private def bindDpopKey(
        params: Map[String, Chunk[String]],
        proofJkt: Option[String],
    ): IO[PushedAuthorizationError, Map[String, Chunk[String]]] =
      proofJkt match
        case None => ZIO.succeed(params)
        case Some(jkt) =>
          val stated = params.get(Dpop.Jkt.Parameter).map(_.toList).getOrElse(Nil)
          if stated.forall(_ == jkt) then ZIO.succeed(params.updated(Dpop.Jkt.Parameter, Chunk(jkt)))
          else ZIO.fail(PushedAuthorizationError.InvalidDpopProof(
            s"the ${Dpop.Jkt.Parameter} parameter does not match the key of the DPoP proof",
          ))
