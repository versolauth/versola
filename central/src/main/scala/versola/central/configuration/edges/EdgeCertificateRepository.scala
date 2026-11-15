package versola.central.configuration.edges

import zio.Task

import java.time.Instant

/** A record of each certificate central issued to an edge, so an operator can see what is about to expire. */
trait EdgeCertificateRepository:
  def record(edgeId: EdgeId, serial: String, notAfter: Instant, issuedAt: Instant): Task[Unit]

  /** The certificate last issued to each edge that has one: what `GET /configuration/edges` shows as the edge's
    * certificate, since the certificate itself is not stored. */
  def latest: Task[Map[EdgeId, EdgeCertificateRepository.Issued]]

  /** How many of the certificates last issued to each edge are past the middle of their renewal window at `now`: an edge renews in the
    * last `renewalShare` of its certificate's life, so less than half of that share of the life is left. An expired one is included. The
    * edges of a suspended partner are left out: they are refused renewal on purpose, and their certificates are meant to run out. */
  def pastRenewalMidpoint(now: Instant, renewalShare: Double): Task[Long]

object EdgeCertificateRepository:
  case class Issued(serial: String, notAfter: Instant, issuedAt: Instant)
