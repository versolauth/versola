package versola.central.configuration.edges

import versola.util.CertificateMetrics
import zio.Task

import java.time.Instant

/** A record of each certificate central issued to an edge, so an operator can see what is about to expire. */
trait EdgeCertificateRepository:
  def record(edgeId: EdgeId, serial: String, notAfter: Instant, issuedAt: Instant): Task[Unit]

  /** The certificate last issued to each edge that has one: what `GET /configuration/edges` shows as the edge's
    * certificate, since the certificate itself is not stored. */
  def latest: Task[Map[EdgeId, EdgeCertificateRepository.Issued]]

  /** What the certificate last issued to each edge has left at `now`, as one aggregate. An edge renews in the last `renewalShare` of its
    * certificate's life, so that is its renewal window and its middle is half of it. The edges of a suspended partner are left out: they
    * are refused renewal on purpose, and their certificates are meant to run out. */
  def expiry(now: Instant, renewalShare: Double): Task[CertificateMetrics.Expiry]

object EdgeCertificateRepository:
  case class Issued(serial: String, notAfter: Instant, issuedAt: Instant)
