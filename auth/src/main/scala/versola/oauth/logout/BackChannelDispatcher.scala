package versola.oauth.logout

import versola.oauth.client.model.OAuthClientRecord
import versola.oauth.jwks.JwksService
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.http.{Body, Client, Form, Request, URL}
import zio.json.ast.Json

/** Delivers an OP-signed security event token to a client's `back_channel_logout_uri`.
  *
  * The transport is the one OIDC Back-Channel Logout defines, but it is not specific to
  * logout: that URI is already the address of the component guarding the client's
  * resources, and it already trusts tokens signed by this OP. Anything the OP has to push
  * to a client out of band — a logout, a revoked token — travels the same way and differs
  * only in the event it carries.
  */
trait BackChannelDispatcher:
  /** Signs and posts one event. One attempt, bounded by a timeout, no retries: the caller
    * decides whether to wait for it and what a failure means.
    */
  def dispatch(
      client: OAuthClientRecord,
      uri: URL,
      subject: String,
      customClaims: Json.Obj,
  ): Task[Unit]

object BackChannelDispatcher:
  /** How long a token issued to an RP is valid for. */
  private val TokenTtl = 2.minutes

  /** How long the OP waits for an RP's endpoint to respond before giving up on that single
    * delivery attempt.
    */
  private val RequestTimeout = 5.seconds

  val live = ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      config: CoreConfig,
      jwksService: JwksService,
      httpClient: Client,
  ) extends BackChannelDispatcher:

    override def dispatch(
        client: OAuthClientRecord,
        uri: URL,
        subject: String,
        customClaims: Json.Obj,
    ): Task[Unit] =
      deliver(client, uri, subject, customClaims)
        .timeoutFail(RuntimeException(s"back-channel delivery to client '${client.id}' timed out"))(RequestTimeout)

    private def deliver(
        client: OAuthClientRecord,
        uri: URL,
        subject: String,
        customClaims: Json.Obj,
    ): Task[Unit] =
      for
        signingKey <- jwksService.getPublicKeys.map(_.active)
        token <- sign(client, subject, customClaims, signingKey)
        request = Request.post(uri, Body.fromURLEncodedForm(Form.fromStrings("logout_token" -> token)))
        _ <- ZIO.scoped:
          httpClient.request(request).flatMap: response =>
            ZIO.unless(response.status.isSuccess):
              // An RP that rejects an event says why in its response (OIDC Back-Channel
              // Logout §2.8), and that reason is the only thing that distinguishes a
              // misconfigured client from a broken one.
              response.body.asString.orElseSucceed("").flatMap: body =>
                ZIO.fail(RuntimeException(
                  s"back-channel endpoint responded with ${response.status.code}: ${body.take(200)}",
                ))
      yield ()

    private def sign(
        client: OAuthClientRecord,
        subject: String,
        customClaims: Json.Obj,
        signingKey: JWT.PublicKey,
    ): Task[String] =
      JWT.serialize(
        claims = JWT.Claims(
          issuer = config.jwt.issuer,
          subject = subject,
          audience = List(client.id),
          custom = customClaims,
        ),
        ttl = TokenTtl,
        signature = JWT.Signature.Asymmetric(
          algorithm = signingKey.algorithm,
          keyId = signingKey.id,
          privateKey = config.jwt.privateKey,
        ),
      )
