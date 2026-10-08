package versola.central.configuration.clients.certificates

import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeId
import zio.*

import java.time.Instant

/** Which clients have their edge generate the key and enrol for a certificate (#463). Holds no
  * certificate and no key. */
trait EdgeCertificateEnrollmentRepository:

  def enroll(clientId: ClientId, at: Instant): Task[Unit]

  def isEnrolled(clientId: ClientId): Task[Boolean]

  def enrolledClients: Task[Set[ClientId]]

  /** What was last signed for the client, and for whom. */
  def recordIssued(clientId: ClientId, serial: String, at: Instant, edgeId: EdgeId): Task[Unit]

  def delete(clientId: ClientId): Task[Unit]
