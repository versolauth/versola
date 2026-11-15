package versola.central.configuration.clients.certificates

import versola.central.CentralConfig
import versola.central.configuration.clients.est.ClientEstRepository
import versola.central.configuration.edges.EdgeCertificateRepository
import versola.util.CertificateMetrics.{Expiry, Kind}
import versola.util.{CertificateMetrics, EdgeIdentityNames}
import zio.*

import java.time.Instant

/** Publishes how long the certificates central holds have left (#1001), once for each kind, from the stores that record them: the
  * certificates central issued to clients, the identities of the edges, and the certificates clients obtained through EST.
  *
  * Each kind is read with one aggregate query and set as four gauges (see [[CertificateMetrics.Expiry]]), so what is exported does not grow
  * with the number of clients. A kind that cannot be read keeps the values it had and is logged; the other kinds are still published.
  * Every replica of central publishes the same numbers, which a rule takes the largest of.
  */
trait CertificateExpiryReporter:
  /** Reads the three stores and sets the gauges, now. */
  def report: UIO[Unit]

object CertificateExpiryReporter:
  /** How often the stores are read: often against the days a certificate lives. Not every minute: the EST store keeps every certificate it
    * ever recorded, and its aggregate reads them all. */
  val Interval: Duration = 5.minutes

  val live: URLayer[
    ClientCertificateIssuanceRepository & EdgeCertificateRepository & ClientEstRepository & CentralConfig,
    CertificateExpiryReporter,
  ] = ZLayer.fromFunction(Impl.apply)

  /** The windows are the ones the holders renew by: central's `renew-before`, an edge's third, EST's `client-identity.renew-window`. */
  class Impl(
      issuances: ClientCertificateIssuanceRepository,
      edges: EdgeCertificateRepository,
      est: ClientEstRepository,
      config: CentralConfig,
  ) extends CertificateExpiryReporter:
    private val renewBefore: Duration =
      config.certificateBackends.getOrElse(CentralConfig.ClientCertificatesConfig()).renewBefore
    private val edgeShare: Double = 1.0 / EdgeIdentityNames.RenewalDivisor
    private val estShare: Double = config.clientIdentity.renewWindow.max(0.0).min(1.0)

    override def report: UIO[Unit] =
      Clock.instant.flatMap: now =>
        publish(Kind.Client, issuances.expiry(now, renewBefore)) *>
          publish(Kind.Edge, edges.expiry(now, edgeShare)) *>
          publish(Kind.Est, est.expiry(now, estShare))

    private def publish(kind: Kind, read: Task[Expiry]): UIO[Unit] =
      read.flatMap(CertificateMetrics.expiry(kind, _))
        .catchAll(error =>
          ZIO.logWarning(s"the expiry of the ${kind.label} certificates could not be read, their gauges keep the last values: $error"),
        )

  /** Publishes for as long as the application runs, and once at once so that a restart does not leave the gauges at nothing for a minute. */
  val reporting: ZLayer[CertificateExpiryReporter, Nothing, Unit] =
    ZLayer.scoped:
      ZIO.serviceWithZIO[CertificateExpiryReporter](_.report.repeat(Schedule.fixed(Interval)).forkScoped).unit
