package versola.edge

import versola.util.PrivateClientCertificate
import zio.{UIO, URLayer, ZLayer}

/** Boundary 2 of #555: how this edge proves to auth which edge it is.
  *
  * Only the calls edge makes on its own behalf need this. The ones it makes as one of the
  * clients it fronts (`/token`, `/par`, a certificate-bound `/userinfo` of its own session)
  * authenticate with that client's credential, which comes with the client's configuration. What
  * is left is the `/userinfo` call for a token whose holder edge verified itself (a key-bound
  * one, or one bound to the certificate of a caller on edge's mutual-TLS listener): auth takes
  * the certificate this edge presents in the handshake as the proof, because that certificate
  * names an edge it trusts (`urn:versola:edge:<id>`).
  */
trait EdgeIdentity:
  /** The certificate and key to present on a call made on this edge's own behalf, or `None` while
    * the edge holds none (it has not enrolled yet). Read per call: central renews it. */
  def certificate: UIO[Option[PrivateClientCertificate.Material]]

object EdgeIdentity:

  /** The identity `EdgeConfig.mode` names. */
  val live: ZLayer[EdgeConfig & EdgeCertificateHolder, Throwable, EdgeIdentity] =
    ZLayer.service[EdgeConfig].flatMap(env => forMode(env.get.mode))

  def forMode(mode: EdgeMode): ZLayer[EdgeCertificateHolder, Throwable, EdgeIdentity] =
    mode match
      case EdgeMode.FirstParty => registered
      // Unreachable through `EdgeConfig.validated`, which refuses the mode first; kept so the
      // boundary is total and a wiring that skips validation fails naming the same cause.
      // #564 puts standard client authentication here: auth holds no edge CA for a partner
      // edge, so its certificate is not one auth can take as proof.
      case EdgeMode.Partner => ZLayer.fail(EdgeMode.NotImplemented(mode))

  /** [[EdgeMode.FirstParty]]: the certificate central issued this edge (or the operator deployed
    * it with), which is the one it presents to central too. */
  val registered: URLayer[EdgeCertificateHolder, EdgeIdentity] = ZLayer.fromFunction(Registered(_))

  class Registered(holder: EdgeCertificateHolder) extends EdgeIdentity:
    override def certificate: UIO[Option[PrivateClientCertificate.Material]] = holder.material
