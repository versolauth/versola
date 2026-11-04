package versola.audit

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.audit.AuditReadRepository.Position
import versola.util.DatabaseSpecBase
import versola.util.audit.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.util.UUID

/** What every storage of the audit log promises: the partitions it keeps (made on request, made again without error, old ones
  * dropped, the rows of a day without a partition kept where they are and not moved), the batch it writes and reads back whole, the
  * pages and the summary of one user's events, and the default partition's rows pruned past the retention. The rows are written
  * through [[AuditEventRepository]] and read through [[AuditReadRepository]], as the service does; what only one storage promises
  * (the trigger that makes the table append-only, the names of its partitions) goes in the runner's `implementationCases`. */
trait AuditEventRepositorySpec extends DatabaseSpecBase[AuditEventRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AuditEventRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    val reader = env.reader
    val f = env.fixtures

    def eventsOf(user: UUID, from: Instant, to: Instant, limit: Int = 100) = reader.userEvents(user, from, to, None, limit)

    val wholeDay = (utc(today.atTime(0, 0)), utc(today.atTime(23, 59)))

    List(
      test("creates a partition for each requested day, and again without error") {
        for
          first <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          second <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          ahead <- repository.partitionDaysAhead(today)
        yield assertTrue(first.created == 3, second.created == 0, second.daysLeftInDefault.isEmpty, ahead == 2)
      },
      test("writes a batch in one go and reads every column back") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          full = event(utc(today.atTime(10, 0)), user = Some(user1)).copy(
            tenantId = Some("tenant-a"),
            reason = Some("invalid_grant"),
            actorId = Some(user1.toString),
            clientId = Some("client-a"),
            sessionId = Some("sid-1"),
            ip = Some("2001:db8::1"),
            userAgent = Some("Chrome 130 / macOS"),
            traceId = Some("4bf92f3577b34da6a3ce929d0e0e4736"),
            targetType = Some("user"),
            targetId = Some(user1.toString),
            details = Json.Obj("grant" -> Json.Str("refresh_token"), "scope" -> Json.Arr(Json.Str("openid"))),
          )
          sparse = event(utc(today.atTime(11, 0)), user = Some(user1)).copy(ip = Some("203.0.113.7"))
          _ <- repository.insertBatch(List(full, sparse))
          read <- eventsOf(user1, wholeDay._1, wholeDay._2)
        yield assertTrue(read == List(sparse, full))
      },
      test("an empty batch writes nothing") {
        for
          _ <- repository.insertBatch(Nil)
          rows <- f.countEvents
        yield assertTrue(rows == 0L)
      },
      test("reads one user's events, newest first, inside the window and no one else's") {
        for
          _ <- repository.maintainPartitions(from = today, days = 2, keepFrom = today.minusDays(30))
          early = event(utc(today.atTime(8, 0)), user = Some(user1))
          late = event(utc(today.atTime(20, 0)), user = Some(user1))
          nextDay = event(utc(today.plusDays(1).atTime(8, 0)), user = Some(user1))
          other = event(utc(today.atTime(12, 0)), user = Some(user2))
          anonymous = event(utc(today.atTime(12, 30)), user = None)
          _ <- repository.insertBatch(List(early, other, anonymous, late, nextDay))
          day <- eventsOf(user1, wholeDay._1, wholeDay._2)
          both <- eventsOf(user1, wholeDay._1, utc(today.plusDays(1).atTime(23, 59)))
          morning <- eventsOf(user1, utc(today.atTime(7, 0)), utc(today.atTime(9, 0)))
          second <- eventsOf(user2, wholeDay._1, wholeDay._2)
        yield assertTrue(
          day.map(_.id) == List(late.id, early.id),
          both.map(_.id) == List(nextDay.id, late.id, early.id),
          morning.map(_.id) == List(early.id),
          second.map(_.id) == List(other.id),
        )
      },
      test("pages by the position of the last event, without missing or repeating one that shares an instant") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          same = utc(today.atTime(10, 0))
          events = (1 to 5).toList.map(_ => event(same, user = Some(user1))) :+ event(utc(today.atTime(9, 0)), user = Some(user1))
          _ <- repository.insertBatch(events)
          first <- eventsOf(user1, wholeDay._1, wholeDay._2, limit = 2)
          second <- reader.userEvents(user1, wholeDay._1, wholeDay._2, first.lastOption.map(e => Position(e.at, e.id)), 2)
          third <- reader.userEvents(user1, wholeDay._1, wholeDay._2, second.lastOption.map(e => Position(e.at, e.id)), 2)
          fourth <- reader.userEvents(user1, wholeDay._1, wholeDay._2, third.lastOption.map(e => Position(e.at, e.id)), 2)
          paged = (first ++ second ++ third).map(_.id)
        yield assertTrue(
          paged.size == 6,
          paged.toSet == events.map(_.id).toSet,
          // the order of a page is the order of the next: nothing newer follows an older event
          (first ++ second ++ third).map(_.at) == (first ++ second ++ third).map(_.at).sorted.reverse,
          fourth.isEmpty,
        )
      },
      test("summarizes a user's events: by kind, client, address and user agent, sessions, factors and the severe ones") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          login = event(utc(today.atTime(9, 0)), user = Some(user1), kind = AuditKinds.LoginCompleted).copy(
            clientId = Some("client-a"),
            ip = Some("203.0.113.7"),
            userAgent = Some("Chrome 130 / macOS"),
          )
          token = event(utc(today.atTime(9, 1)), user = Some(user1), kind = AuditKinds.TokenIssued).copy(
            clientId = Some("client-a"),
            ip = Some("203.0.113.7"),
            userAgent = Some("Chrome 130 / macOS"),
          )
          refusedToken = event(utc(today.atTime(9, 2)), user = Some(user1), kind = AuditKinds.TokenRefused, outcome = AuditOutcome.failure)
            .copy(clientId = Some("client-b"), ip = Some("198.51.100.9"), userAgent = Some("Firefox 131 / Linux"))
          password = event(utc(today.atTime(9, 3)), user = Some(user1), kind = AuditKinds.AccountPasswordChanged)
          passkey = event(utc(today.atTime(9, 4)), user = Some(user1), kind = AuditKinds.AccountPasskeyEnrolled)
          reuse = event(utc(today.atTime(9, 5)), user = Some(user1), kind = AuditKinds.TokenReuseDetected, outcome = AuditOutcome.denied)
            .copy(severity = AuditSeverity.high)
          ended = event(utc(today.atTime(9, 6)), user = Some(user1), kind = AuditKinds.SessionEnded)
          other = event(utc(today.atTime(9, 7)), user = Some(user2), kind = AuditKinds.LoginCompleted)
          _ <- repository.insertBatch(List(login, token, refusedToken, password, passkey, reuse, ended, other))
          summary <- reader.userSummary(user1, wholeDay._1, wholeDay._2)
        yield assertTrue(
          summary.byKind.map(k => (k.kind, k.outcome, k.count)).toSet == Set(
            ("login.completed", "success", 1L),
            ("token.issued", "success", 1L),
            ("token.refused", "failure", 1L),
            ("account.password_changed", "success", 1L),
            ("account.passkey_enrolled", "success", 1L),
            ("token.reuse_detected", "denied", 1L),
            ("session.ended", "success", 1L),
          ),
          summary.byClient.map(c => (c.clientId, c.logins, c.tokens)) == List(("client-a", 1L, 1L), ("client-b", 0L, 0L)),
          summary.ips.map(i => (i.ip, i.count)) == List(("203.0.113.7", 2L), ("198.51.100.9", 1L)),
          summary.ips.head.firstSeen == login.at && summary.ips.head.lastSeen == token.at,
          summary.userAgents.map(a => (a.userAgent, a.count)) == List(("Chrome 130 / macOS", 2L), ("Firefox 131 / Linux", 1L)),
          summary.sessions.created == 1L && summary.sessions.ended == 1L,
          summary.factors.map(_.kind) == List("account.passkey_enrolled", "account.password_changed"),
          summary.high.map(_.id) == List(reuse.id),
        )
      },
      test("the summary of a user with no events is empty, not an error") {
        for summary <- reader.userSummary(user1, wholeDay._1, wholeDay._2)
        yield assertTrue(
          summary.byKind.isEmpty,
          summary.byClient.isEmpty,
          summary.ips.isEmpty,
          summary.userAgents.isEmpty,
          summary.sessions.created == 0L && summary.sessions.ended == 0L,
          summary.factors.isEmpty,
          summary.high.isEmpty,
        )
      },
      test("an event of a day with no partition is kept and read, and never moved: the day is reported and not attached") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          late = event(utc(today.plusDays(3).atTime(8, 0)), user = Some(user1))
          _ <- repository.insertBatch(List(late))
          held <- f.countUnpartitioned
          read <- eventsOf(user1, utc(today.plusDays(3).atTime(0, 0)), utc(today.plusDays(3).atTime(23, 0)))
          report <- repository.maintainPartitions(from = today, days = 7, keepFrom = today.minusDays(30))
          heldAfter <- f.countUnpartitioned
          again <- repository.maintainPartitions(from = today, days = 7, keepFrom = today.minusDays(30))
          ahead <- repository.partitionDaysAhead(today)
        yield assertTrue(
          held == 1L,
          read.map(_.id) == List(late.id),
          report.movedFromDefault == 0L,
          report.daysLeftInDefault == List(today.plusDays(3)),
          heldAfter == 1L,
          again.daysLeftInDefault == List(today.plusDays(3)),
          // the day with rows in the default is not attached, the others are: today (made above) and 1, 2, 4, 5, 6
          ahead == 5,
        )
      },
      test("deletes from the default partition only what is older than the limit, and the day is attached once its rows are gone") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          old = event(utc(today.plusDays(3).atTime(8, 0)), user = Some(user1))
          kept = event(utc(today.plusDays(3).atTime(12, 0)), user = Some(user1))
          _ <- repository.insertBatch(List(old, kept))
          deleted <- repository.pruneDefault(utc(today.plusDays(3).atTime(10, 0)))
          held <- f.countUnpartitioned
          nothing <- repository.pruneDefault(utc(today.plusDays(3).atTime(10, 0)))
          _ <- repository.pruneDefault(utc(today.plusDays(4).atTime(0, 0)))
          report <- repository.maintainPartitions(from = today, days = 7, keepFrom = today.minusDays(30))
        yield assertTrue(deleted == 1L, held == 1L, nothing == 0L, report.daysLeftInDefault.isEmpty)
      },
      test("drops the partitions of the days that are gone, and with them their rows") {
        for
          _ <- repository.maintainPartitions(from = today.minusDays(3), days = 4, keepFrom = today.minusDays(30))
          gone = event(utc(today.minusDays(3).atTime(8, 0)), user = Some(user1))
          kept = event(utc(today.minusDays(1).atTime(8, 0)), user = Some(user1))
          _ <- repository.insertBatch(List(gone, kept))
          report <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(2))
          read <- eventsOf(user1, utc(today.minusDays(4).atTime(0, 0)), utc(today.atTime(0, 0)))
        yield assertTrue(report.dropped == 1, read.map(_.id) == List(kept.id))
      },
      test("the service owns the table in a fresh database") {
        for ownership <- repository.ownership
        yield assertTrue(ownership.mayAlter)
      },
    )

object AuditEventRepositorySpec:
  case class Env(repository: AuditEventRepository, reader: AuditReadRepository, fixtures: Fixtures)

  /** What a case reads of the table that the repository does not offer, and the runner supplies. */
  trait Fixtures:
    def countEvents: Task[Long]

    /** How many events sit in the default partition. */
    def countUnpartitioned: Task[Long]

  val today: LocalDate = LocalDate.of(2026, 10, 21)
  val farFuture: LocalDate = LocalDate.of(2099, 1, 1)

  val user1: UUID = UUID.fromString("0192b3c0-0000-7000-8000-000000000001")
  val user2: UUID = UUID.fromString("0192b3c0-0000-7000-8000-000000000002")

  def utc(time: LocalDateTime): Instant = time.toInstant(ZoneOffset.UTC)

  def event(
      at: Instant,
      user: Option[UUID],
      kind: AuditKind = AuditKinds.LoginStep,
      outcome: AuditOutcome = AuditOutcome.success,
  ): AuditEvent =
    AuditEvent(
      id = UUID.randomUUID(),
      at = at,
      tenantId = None,
      source = AuditSource.auth,
      nodeId = "node-1",
      kind = kind,
      outcome = outcome,
      reason = None,
      severity = AuditSeverity.of(kind, outcome),
      actorType = ActorType.user,
      actorId = user.map(_.toString),
      userId = user,
      clientId = None,
      sessionId = None,
      ip = None,
      userAgent = None,
      traceId = None,
      targetType = None,
      targetId = None,
      details = Json.Obj(),
    )
