package versola.oauth.authorize

import versola.oauth.authorize.model.ResponseMode
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.jwks.JwksService
import versola.util.{CoreConfig, JWT}
import zio.http.URL
import zio.json.ast.Json
import zio.{Chunk, Task, ZLayer, durationInt}

/** Builds the redirect an authorization response is delivered on, for successes and errors
  * alike.
  *
  * Under a plain response mode this is parameter placement and nothing more. Under JARM
  * (`response_mode=jwt` and its `query.jwt` / `fragment.jwt` forms) every parameter moves
  * inside a `response` JWT signed with the client's tenant key, so a client can tell an
  * authorization response this server produced from one an attacker assembled -- which is
  * why error responses go through here too, rather than being formatted at the call site.
  */
trait AuthorizationResponseService:
  def redirect(
      clientId: ClientId,
      redirectUri: URL,
      mode: ResponseMode,
      params: List[(String, String)],
  ): Task[URL]

object AuthorizationResponseService:
  def live: ZLayer[CoreConfig & OAuthConfigurationService & JwksService, Nothing, AuthorizationResponseService] =
    ZLayer.fromFunction(Impl(_, _, _))

  /** JARM §2.1 requires an `exp` and recommends a lifetime of at most 10 minutes. The response
    * is redeemed by the browser redirect that carries it, so it only has to outlive that hop,
    * not the code it delivers.
    */
  private val ResponseTtl = 5.minutes

  class Impl(
      config: CoreConfig,
      configurationService: OAuthConfigurationService,
      jwksService: JwksService,
  ) extends AuthorizationResponseService:

    override def redirect(
        clientId: ClientId,
        redirectUri: URL,
        mode: ResponseMode,
        params: List[(String, String)],
    ): Task[URL] =
      if mode.isJwt then
        signedResponse(clientId, params)
          .map(jwt => AuthorizeRedirect.responseUrl(redirectUri, List(AuthorizeRedirect.JwtParameter -> jwt), mode))
      else
        zio.ZIO.succeed(AuthorizeRedirect.responseUrl(redirectUri, params, mode))

    /** JARM §2.1: the response parameters become claims of a JWT issued by this server to the
      * client the response is addressed to. `iss` is dropped from the parameters first -- the
      * JWT carries the issuer as its own claim, and an `iss` parameter alongside it would be
      * the same statement twice, with only the unsigned copy able to disagree.
      *
      * The algorithm is the tenant signing key's own, which JARM §2.2 leaves to the server --
      * basing it on a client's `authorization_signed_response_alg` is a "can", and that
      * parameter is not registrable here. What the key is published under is therefore what a
      * client has to verify with, so the discovery document states it
      * (`authorization_signing_alg_values_supported`, JARM §4) rather than leaving a client to
      * assume §3's `RS256` default.
      */
    private def signedResponse(clientId: ClientId, params: List[(String, String)]): Task[String] =
      for
        client <- configurationService.get(clientId)
        signature <- jwksService.signingKey(client.tenantId)
        claims = params.filterNot(_._1 == "iss").map((key, value) => key -> Json.Str(value))
        token <- JWT.serialize(
          claims = JWT.Claims(
            issuer = config.jwt.issuer,
            subject = clientId,
            audience = List(clientId),
            custom = Json.Obj(Chunk.fromIterable(claims)),
          ),
          ttl = ResponseTtl,
          signature = signature,
        )
      yield token
