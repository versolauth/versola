package versola.audit

import versola.util.audit.AuditEvent
import zio.Task
import zio.json.{JsonCodec, JsonEncoder}

import java.time.Instant
import java.util.UUID

/** The read side of auth's audit log: what the per-user report (central's, over [[AuditController]]) asks for. Every read goes by
  * `user_id` and a time window, through the index on `(user_id, at)`. */
trait AuditReadRepository:

  /** The events about `userId` in `[from, to]`, newest first, after `after` (the last event of the previous page) when given. */
  def userEvents(userId: UUID, from: Instant, to: Instant, after: Option[AuditCursor], limit: Int): Task[List[AuditEvent]]

  def userSummary(userId: UUID, from: Instant, to: Instant): Task[AuditReadRepository.Summary]

object AuditReadRepository:

  case class KindCount(kind: String, outcome: String, count: Long) derives JsonCodec
  case class ClientCount(clientId: String, logins: Long, tokens: Long) derives JsonCodec
  case class IpCount(ip: String, firstSeen: Instant, lastSeen: Instant, count: Long) derives JsonCodec
  case class UserAgentCount(userAgent: String, firstSeen: Instant, lastSeen: Instant, count: Long) derives JsonCodec
  case class SessionCount(created: Long, ended: Long) derives JsonCodec
  case class Factor(kind: String, at: Instant) derives JsonCodec

  /** What `GET /audit/summary` answers, less the window, which the service adds. */
  case class Summary(
      byKind: List[KindCount],
      byClient: List[ClientCount],
      ips: List[IpCount],
      userAgents: List[UserAgentCount],
      sessions: SessionCount,
      factors: List[Factor],
      high: List[AuditEvent],
  )

  /** How many addresses and user agents, and severe events, the summary lists. */
  val TopLimit = 50
  val FactorLimit = 100
