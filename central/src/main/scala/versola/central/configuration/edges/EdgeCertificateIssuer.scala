package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.central.configuration.clients.certificates.ClientCertificateIssuer
import zio.*
import zio.http.Client

/** What signs the certificates of edges, kept apart from what signs clients' so that the two cannot be told for one
  * another: central's mutual-TLS listener trusts the CA of this one for edges, auth the other for clients, and a client's
  * certificate must not pass the handshake of the edge surface.
  *
  * `edge-certificates` (a backend) or `edge-certificate-authority` (a CA central holds the key of) name it. Where neither
  * is set the client issuer is used, which is right for the one deployment that has a single CA and wrong for any that
  * has two: an edge certificate signed by the client CA is refused in the handshake, which is how it shows.
  */
final case class EdgeCertificateIssuer(issuer: ClientCertificateIssuer)

object EdgeCertificateIssuer:
  val live: ZLayer[CentralConfig & Client & ClientCertificateIssuer, Throwable, EdgeCertificateIssuer] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[CentralConfig]
        client <- ZIO.service[Client]
        shared <- ZIO.service[ClientCertificateIssuer]
        issuer <-
          if config.edgeCertificateBackends.isDefined || config.edgeCertificateAuthority.isDefined then
            ClientCertificateIssuer.from(
              config.edgeCertificateBackends,
              config.edgeCertificateAuthority,
              client,
              "edge-certificates",
              "edge-certificate-authority",
            )
          else ZIO.succeed(shared)
      yield EdgeCertificateIssuer(issuer)

  /** The lifetime asked of the CA: `edge-certificates.validity` when that names a CA, else the clients'. */
  def validity(config: CentralConfig): Duration =
    config.edgeCertificateBackends.orElse(config.certificateBackends).getOrElse(CentralConfig.ClientCertificatesConfig()).validity
