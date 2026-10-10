package versola.oauth.authorize

import versola.oauth.authorize.model.Error
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, SecurityProfile}
import versola.oauth.clientauth.ClientAssertionRepository
import versola.util.http.Observability
import versola.util.{CoreConfig, JwtAudience, RequestObject}
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
  def live: ZLayer[CoreConfig & OAuthConfigurationService & ClientAssertionRepository, Nothing, RequestObjectService] =
    ZLayer.fromFunction(Impl(_, _, _))

  /** @param replayGuard remembers every `(client, jti)` a verified object carried, until its
    *   `exp` -- the same store client assertions are checked against. RFC 7519 §4.1.7 makes a
    *   `jti` unique per issuer across everything it signs, and a client signs both kinds of JWT
    *   with the same registered keys, so one namespace per client is the stricter reading:
    *   a `jti` spent on an assertion cannot be spent again on a request object, or vice versa.
    */
  class Impl(
      config: CoreConfig,
      configurationService: OAuthConfigurationService,
      replayGuard: ClientAssertionRepository,
  ) extends RequestObjectService:

    override def resolve(params: Map[String, Chunk[String]]): IO[Error, Map[String, Chunk[String]]] =
      params.get(RequestObject.Parameter) match
        case None => ZIO.succeed(params)
        case Some(Chunk(token)) => verify(token, params)
        case Some(_) => ZIO.fail(Error.InvalidRequestObject("the request parameter was sent more than once"))

    private def verify(token: String, params: Map[String, Chunk[String]]): IO[Error, Map[String, Chunk[String]]] =
      for
        // §5: the outer client_id is required alongside a request object, and is what selects
        // the key set the signature is checked against.
        clientId <- ZIO.fromOption(params.get("client_id").collect { case Chunk(one) => ClientId(one) })
          .orElseFail(Error.InvalidRequestObject("no single client_id accompanies the request object"))
        // `/authorize` has authenticated nobody, so this is the first point at which the
        // request can be attributed to a client at all -- and every error below is reported
        // without naming its cause, which leaves the log nothing to attribute it by.
        _ <- Observability.setClientId(clientId)

        client <- configurationService.find(clientId)
          .someOrFail(Error.InvalidRequestObject("no such client"))

        keys <- ZIO.fromEither(client.jwks.toRight(()).flatMap(_.publicKeys.left.map(_ => ())))
          // A client with no usable key set cannot sign a request at all, which is a
          // registration the operator has to fix rather than something the caller can.
          .orElseFail(Error.InvalidRequestObject("the client has no usable registered JWK Set"))

        allowedAlgorithms <- configurationService.getRequestObjectSigningAlgorithms
        // The tenant's bound on how far ahead a client-signed JWT may expire. It is the same
        // question for a request object as for an assertion -- how long an observed one stays
        // replayable -- so it is the same setting rather than a second one to keep in step.
        maxLifetime <- configurationService.getClientAssertionMaxLifetime(clientId)
        profile <- configurationService.getSecurityProfile(clientId)
        now <- Clock.instant

        claims <- RequestObject.verify(
          token = token,
          keys = keys,
          allowedAlgorithms = allowedAlgorithms,
          clientId = clientId,
          audience = audience(profile),
          // FAPI 2.0 Message Signing: a `fapi2` tenant requires `nbf`; RFC 9101 alone does not.
          requireNotBefore = profile == SecurityProfile.fapi2,
          now = now,
          maxLifetime = maxLifetime,
        ).mapError(reason => Error.InvalidRequestObject(reason.toString))
        _ <- rejectReplay(clientId, claims, profile)
      yield RequestObject.parameters(claims)

    /** #358 / RFC 9101 §10: a by-value object sent straight to `/authorize` has no PAR-style
      * one-time `request_uri` protecting it, so without this it could be replayed to
      * re-initiate the same signed request for as long as its `exp` allows. Checked after
      * every other rule, so only an object that would otherwise be accepted is recorded --
      * mirroring `ClientAssertionService`.
      *
      * A `fapi2` tenant requires the `jti` this needs; a `standard` one, where RFC 9101 leaves
      * it optional, is protected only when the client sends one.
      */
    private def rejectReplay(
        clientId: ClientId,
        claims: zio.json.ast.Json.Obj,
        profile: SecurityProfile,
    ): IO[Error, Unit] =
      for
        key <- RequestObject.replayKey(claims)
          .mapError(reason => Error.InvalidRequestObject(reason.toString))
        _ <- ZIO.when(key.isEmpty && profile == SecurityProfile.fapi2):
          ZIO.fail(Error.InvalidRequestObject("no jti"))
        _ <- ZIO.foreachDiscard(key): key =>
          replayGuard.recordIfAbsent(clientId, key.jti, key.expiresAt)
            // A replay guard that cannot answer is this server's failure, not the client's: it
            // surfaces as a 500 rather than as `invalid_request_object`, and the request is
            // never admitted unchecked.
            .orDie
            .flatMap: fresh =>
              ZIO.unless(fresh)(ZIO.fail(Error.InvalidRequestObject("jti replayed")))
      yield ()

    /** RFC 9101 §4 names the issuer identifier; §10.3 recommends naming the endpoint the
      * request is for, and clients built against OpenID Connect Core §6.1 send that instead.
      * FAPI 2.0 §5.3.2.1-8 takes the issuer alone, as a string, which is what a `fapi2`
      * tenant is held to; a `standard` tenant still accepts either.
      */
    private def audience(profile: SecurityProfile): JwtAudience = profile match
      case SecurityProfile.fapi2 => JwtAudience.IssuerOnly(config.jwt.issuer)
      case SecurityProfile.standard =>
        JwtAudience.AnyOf(Set(config.jwt.issuer, s"${config.jwt.issuer}/authorize"))
