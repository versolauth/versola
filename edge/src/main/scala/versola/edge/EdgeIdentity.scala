package versola.edge

import versola.edge.model.AccessToken
import versola.util.EdgeAssertion
import zio.http.{Header, Headers}
import zio.{Task, URLayer, ZLayer}

/** Boundary 2 of #555: how this edge proves to auth which edge it is.
  *
  * Only the calls edge makes on its own behalf need this. The ones it makes as one of the
  * clients it fronts (`/token`, `/par`, a certificate-bound `/userinfo`) authenticate with
  * that client's credential, which comes with the client's configuration and is no concern of
  * the edge's own identity. What is left is the `/userinfo` call for a key-bound token, where
  * `SSOClient` asks this for what to carry alongside the token.
  */
trait EdgeIdentity:
  /** The headers a call presenting `accessToken` on this edge's own behalf carries to say
    * which edge made it. Bound to the token, not reusable for another, where the identity
    * admits that at all. */
  def headersFor(accessToken: AccessToken): Task[Headers]

object EdgeIdentity:

  /** The identity `EdgeConfig.mode` names. */
  val live: ZLayer[EdgeConfig, Throwable, EdgeIdentity] =
    ZLayer.service[EdgeConfig].flatMap(env => forMode(env.get.mode))

  def forMode(mode: EdgeMode): ZLayer[EdgeConfig, Throwable, EdgeIdentity] =
    mode match
      case EdgeMode.FirstParty => registered
      // Unreachable through `EdgeConfig.validated`, which refuses the mode first; kept so the
      // boundary is total and a wiring that skips validation fails naming the same cause.
      // #564 puts standard client authentication here: no `EdgeAssertion`, since auth holds
      // no registered key for a partner edge to verify one with.
      case EdgeMode.Partner => ZLayer.fail(EdgeMode.NotImplemented(mode))

  /** [[EdgeMode.FirstParty]]: an edge central registered, which auth recognises by the key
    * in that registration (`EdgeRegistrySyncClient`). */
  val registered: URLayer[EdgeConfig, EdgeIdentity] = ZLayer.fromFunction(Registered(_))

  /** Signs an [[EdgeAssertion]] under this edge's registered key, per call: it is bound to
    * the token in hand, so one captured elsewhere buys nothing for any other token. */
  class Registered(config: EdgeConfig) extends EdgeIdentity:
    override def headersFor(accessToken: AccessToken): Task[Headers] =
      EdgeAssertion.issue(
        edgeId = config.id,
        keyId = config.keyId,
        privateKey = config.privateKey,
        accessToken = accessToken.toString,
      ).map(assertion => Headers(Header.Custom(EdgeAssertion.HeaderName, assertion)))
