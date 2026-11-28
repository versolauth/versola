package versola.edge

import versola.util.PrivateClientCertificate
import zio.{UIO, URLayer, ZIO, ZLayer}

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
      case EdgeMode.Partner => partner

  /** [[EdgeMode.FirstParty]]: the certificate central issued this edge (or the operator deployed
    * it with), which is the one it presents to central too. */
  val registered: URLayer[EdgeCertificateHolder, EdgeIdentity] = ZLayer.fromFunction(Registered(_))

  /** [[EdgeMode.Partner]]: none, whatever certificate the edge holds. A partner edge authenticates to auth as the partner's
    * clients do, with their credential (`PartnerOAuthClientsSyncClient`), and is never an edge auth vouches for: auth's registry
    * excludes partner-kind edges (#962 §5), so the certificate would be refused anyway, and presenting it would be offering a
    * proof that a partner's infrastructure must not be able to make. A call that needs the edge's own certificate (the
    * `/userinfo` of a key-bound token, whose proof the edge verified) is refused by the caller as `EdgeCertificateUnavailable`
    * rather than made without it. */
  val partner: URLayer[EdgeCertificateHolder, EdgeIdentity] = ZLayer.succeed(Absent)

  object Absent extends EdgeIdentity:
    override def certificate: UIO[Option[PrivateClientCertificate.Material]] = ZIO.none

  class Registered(holder: EdgeCertificateHolder) extends EdgeIdentity:
    override def certificate: UIO[Option[PrivateClientCertificate.Material]] = holder.material
