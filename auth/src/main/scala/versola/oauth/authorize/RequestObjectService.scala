package versola.oauth.authorize

import versola.oauth.authorize.model.Error
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.util.{CoreConfig, RequestObject}
import zio.{Chunk, Clock, IO, ZIO, ZLayer}

/** RFC 9101 §6: resolves a `request` parameter into the authorization request parameters it
  * carries, having verified it against the keys the named client registered.
  *
  * The client is looked up by the `client_id` sent outside the object, because that is the
  * only thing about the request that is readable before there is a key to verify it with --
  * §6.3 then requires the object's own `client_id` to be the same value, so nothing is
  * decided on the outer one. Mirrors `versola.oauth.clientauth.ClientAssertionService`, which
  * does the same for a client assertion signed by the same key set.
  */
trait RequestObjectService:
  /** The parameter set to validate the request against.
    *
    * Without a `request` parameter this is the caller's own set, unchanged. With one, it is
    * the object's claims and nothing else: RFC 9101 §5 has the server use only what the
    * object carries, even where a query parameter repeats it, so a parameter added outside
    * the signature cannot reach the request.
    */
  def resolve(params: Map[String, Chunk[String]]): IO[Error, Map[String, Chunk[String]]]

object RequestObjectService:
  def live: ZLayer[CoreConfig & OAuthConfigurationService, Nothing, RequestObjectService] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      configurationService: OAuthConfigurationService,
  ) extends RequestObjectService:

    override def resolve(params: Map[String, Chunk[String]]): IO[Error, Map[String, Chunk[String]]] =
      params.get(RequestObject.Parameter) match
        case None => ZIO.succeed(params)
        case Some(Chunk(token)) => verify(token, params)
        case Some(_) => ZIO.fail(Error.InvalidRequestObject)

    private def verify(token: String, params: Map[String, Chunk[String]]): IO[Error, Map[String, Chunk[String]]] =
      for
        // §5: the outer client_id is required alongside a request object, and is what selects
        // the key set the signature is checked against.
        clientId <- ZIO.fromOption(params.get("client_id").collect { case Chunk(one) => ClientId(one) })
          .orElseFail(Error.InvalidRequestObject)

        client <- configurationService.find(clientId).someOrFail(Error.InvalidRequestObject)

        keys <- ZIO.fromEither(client.jwks.toRight(()).flatMap(_.publicKeys.left.map(_ => ())))
          .orElseFail(Error.InvalidRequestObject)
          .tapError: _ =>
            // A client with no usable key set cannot sign a request at all, which is a
            // registration the operator has to fix rather than something the caller can.
            ZIO.logWarning(s"Client $clientId sent a request object but has no usable registered JWK Set")

        allowedAlgorithms <- configurationService.getRequestObjectSigningAlgorithms
        // The tenant's bound on how far ahead a client-signed JWT may expire. It is the same
        // question for a request object as for an assertion -- how long an observed one stays
        // replayable -- so it is the same setting rather than a second one to keep in step.
        maxLifetime <- configurationService.getClientAssertionMaxLifetime(clientId)
        now <- Clock.instant

        claims <- RequestObject.verify(
          token = token,
          keys = keys,
          allowedAlgorithms = allowedAlgorithms,
          clientId = clientId,
          acceptedAudiences = acceptedAudiences,
          now = now,
          maxLifetime = maxLifetime,
        ).tapError(reason => ZIO.logInfo(s"Rejected the request object of $clientId: $reason"))
          .orElseFail(Error.InvalidRequestObject)
      yield RequestObject.parameters(claims)

    /** RFC 9101 §4 names the issuer identifier; §10.3 recommends naming the endpoint the
      * request is for, and clients built against OpenID Connect Core §6.1 send that instead.
      */
    private val acceptedAudiences: Set[String] =
      Set(config.jwt.issuer, s"${config.jwt.issuer}/authorize")
