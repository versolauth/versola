package versola.central.configuration.clients.est

import versola.central.configuration.clients.ClientId
import zio.Task

import java.time.Instant

/** A certificate issued to a client through EST. Only what is needed to recognise it when it comes back to renew (its
  * SHA-256 thumbprint) and to show an operator what is about to expire; the certificate itself is not kept. */
case class EstCertificate(
    clientId: ClientId,
    thumbprint: String,
    serial: String,
    notAfter: Instant,
    issuedAt: Instant,
)

/** Where a client with EST enrolment stands, for the console and the API. */
case class EstEnrollmentRow(
    clientId: ClientId,
    enrolledAt: Instant,
    chainStartedAt: Option[Instant],
    latestNotAfter: Option[Instant],
)

/** What central keeps for clients that obtain their certificate through EST (see [[ClientEstService]]): which clients
  * do, the one-time tokens they enrol with (only the hash of a token is kept), when each chain of renewals began, and a
  * record of each certificate issued. */
trait ClientEstRepository:
  def enable(clientId: ClientId, at: Instant): Task[Unit]

  def isEnabled(clientId: ClientId): Task[Boolean]

  def all: Task[Vector[EstEnrollmentRow]]

  def createToken(tokenHash: String, clientId: ClientId, expiresAt: Instant): Task[Unit]

  /** The client a token is for, if it is still good (known, unspent, unexpired), without spending it. */
  def peekToken(tokenHash: String, now: Instant): Task[Option[ClientId]]

  /** Spends the token in one statement: `None` when it is unknown, already spent or past `expiresAt`. Two concurrent
    * calls with the same token cannot both succeed. */
  def consumeToken(tokenHash: String, now: Instant): Task[Option[ClientId]]

  /** Gives back a token spent by [[consumeToken]] whose enrolment then failed on our side (the CA was down), so that the
    * operator is not asked for a new one. Only ever called by the one request that spent it. */
  def releaseToken(tokenHash: String): Task[Unit]

  /** Begins the chain of `clientId` at `at`, or moves its start there. */
  def startChain(clientId: ClientId, at: Instant): Task[Unit]

  def chainStartedAt(clientId: ClientId): Task[Option[Instant]]

  def recordCertificate(certificate: EstCertificate): Task[Unit]

  def findCertificate(thumbprint: String): Task[Option[EstCertificate]]
