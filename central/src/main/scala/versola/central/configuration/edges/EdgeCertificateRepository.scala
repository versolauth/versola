package versola.central.configuration.edges

import zio.Task

import java.time.Instant

/** A record of each certificate central issued to an edge, so an operator can see what is about to expire. */
trait EdgeCertificateRepository:
  def record(edgeId: EdgeId, serial: String, notAfter: Instant, issuedAt: Instant): Task[Unit]

  /** The certificate last issued to each edge that has one: what `GET /configuration/edges` shows as the edge's
    * certificate, since the certificate itself is not stored. */
  def latest: Task[Map[EdgeId, EdgeCertificateRepository.Issued]]

object EdgeCertificateRepository:
  case class Issued(serial: String, notAfter: Instant, issuedAt: Instant)
