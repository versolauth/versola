package versola.central.configuration.edges

import zio.Task

import java.time.Instant

/** A record of each certificate central issued to an edge, so an operator can see what is about to expire. */
trait EdgeCertificateRepository:
  def record(edgeId: EdgeId, serial: String, notAfter: Instant, issuedAt: Instant): Task[Unit]

  /** When the first certificate central issued to this edge was issued: the start of the chain of renewals, which is
    * what an absolute limit on that chain is measured from. */
  def firstIssuedAt(edgeId: EdgeId): Task[Option[Instant]]
