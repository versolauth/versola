package versola.central.configuration.clients.certificates

import versola.central.configuration.clients.ClientId
import zio.*

import java.time.Instant

/** A client whose certificate central issued, and so renews. Absence is how an operator-supplied
  * `edgeClientCertificate` (a certificate central did not issue) stays untouched.
  *
  * Holds no key material -- the certificate itself is `oauth_clients.edge_client_certificate`.
  * Kept as its own record, not a flag on the client, so that who issued what, when and for how
  * long can grow into the audit trail #462 designs without touching the client table.
  */
case class ClientCertificateIssuance(
    clientId: ClientId,
    serial: String,
    notAfter: Instant,
    issuedAt: Instant,
)

trait ClientCertificateIssuanceRepository:

  def upsert(issuance: ClientCertificateIssuance): Task[Unit]

  /** Updates the issuance of a client central still manages; `false` when there is none -- an
    * operator replaced the certificate in the meantime, and a renewal must not take it back. */
  def replace(issuance: ClientCertificateIssuance): Task[Boolean]

  def find(clientId: ClientId): Task[Option[ClientCertificateIssuance]]

  /** Forgets that central manages this client's certificate: an operator replaced it. */
  def delete(clientId: ClientId): Task[Unit]

  /** Issuances whose certificate expires before `deadline`. */
  def expiringBefore(deadline: Instant): Task[Vector[ClientCertificateIssuance]]
