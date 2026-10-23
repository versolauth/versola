package versola.central.configuration.clients.est

import versola.central.configuration.clients.ClientId
import zio.{Duration, Task}

import java.time.Instant

/** A certificate to record as issued to a client through EST. Only what is needed to recognise it when it comes back to
  * renew (its SHA-256 thumbprint) and to show an operator what is about to expire; the certificate itself is not kept. */
case class NewEstCertificate(
    clientId: ClientId,
    thumbprint: String,
    serial: String,
    notAfter: Instant,
    issuedAt: Instant,
)

/** A recorded certificate. `generation` is the enrolment it belongs to: the right to renew is the real credential, and
  * it belongs to one generation of a client's chain. A re-enrolment, a revocation and a detected fork each start a new
  * generation, and a certificate of an earlier one can no longer renew. `supersededAt` is when it was replaced by a
  * renewal; one that renews again long after that is a copy of the chain being used (see [[ClientEstService]]). */
case class EstCertificate(
    clientId: ClientId,
    thumbprint: String,
    serial: String,
    notAfter: Instant,
    issuedAt: Instant,
    generation: Int,
    supersededAt: Option[Instant],
)

/** Where a client with EST enrolment stands, for the console and the API: `latestNotAfter` is of the current generation. */
case class EstEnrollmentRow(
    clientId: ClientId,
    enrolledAt: Instant,
    chainStartedAt: Option[Instant],
    latestNotAfter: Option[Instant],
)

/** What came of recording a renewal. */
enum RenewalRecorded:
  /** Recorded; the chain of renewals began at `chainStartedAt` (now, if it had ended and was extended). */
  case Recorded(chainStartedAt: Instant)

  /** The client was re-enrolled or revoked while the CA was answering: the renewal is not recorded. */
  case GenerationChanged

  /** The chain ended while the CA was answering, and may not be extended. */
  case ChainEnded

/** What central keeps for clients that obtain their certificate through EST (see [[ClientEstService]]): which clients
  * do, the one-time tokens they enrol with (only the hash of a token is kept), the generation and the start of each
  * chain, and a record of each certificate issued. */
trait ClientEstRepository:
  def enable(clientId: ClientId, at: Instant): Task[Unit]

  def isEnabled(clientId: ClientId): Task[Boolean]

  /** The current generation of the client's chain. */
  def generation(clientId: ClientId): Task[Option[Int]]

  def all: Task[Vector[EstEnrollmentRow]]

  /** A new token. It replaces the client's earlier tokens that were never used: one token is open at a time. */
  def createToken(tokenHash: String, clientId: ClientId, expiresAt: Instant): Task[Unit]

  /** The client a token is for, if it is still good (known, unspent, unexpired), without spending it. */
  def peekToken(tokenHash: String, now: Instant): Task[Option[ClientId]]

  /** Spends the token in one statement: `None` when it is unknown, already spent or past `expiresAt`. Two concurrent
    * calls with the same token cannot both succeed. */
  def consumeToken(tokenHash: String, now: Instant): Task[Option[ClientId]]

  /** Gives back a token spent by [[consumeToken]] whose enrolment then failed in the CA (before it signed), so that the
    * operator is not asked for a new one. Only ever called by the one request that spent it. */
  def releaseToken(tokenHash: String): Task[Unit]

  /** Ends the right of every certificate issued so far to renew (a new generation), and withdraws the tokens not yet
    * used. The chain has no start until the client enrols again. */
  def revoke(clientId: ClientId): Task[Unit]

  /** Records the certificate of an enrolment in one transaction: a new generation (so no certificate issued before can
    * renew), the chain beginning at `now`, and the certificate in it. */
  def recordEnrollment(certificate: NewEstCertificate, now: Instant): Task[Unit]

  /** Records the certificate of a renewal in one transaction, with the client's row locked: only if the client is still of
    * `generation` and the chain has not ended (`maxAge`; extended by itself where `autoExtend`), and marking
    * `presentedThumbprint` as replaced by it. */
  def recordRenewal(
      presentedThumbprint: String,
      certificate: NewEstCertificate,
      generation: Int,
      now: Instant,
      maxAge: Duration,
      autoExtend: Boolean,
  ): Task[RenewalRecorded]

  /** Starts the chain of `clientId` over at `at`. */
  def extendChain(clientId: ClientId, at: Instant): Task[Unit]

  def chainStartedAt(clientId: ClientId): Task[Option[Instant]]

  def findCertificate(thumbprint: String): Task[Option[EstCertificate]]
