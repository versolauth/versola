package versola.antifraud

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.util.UUID

/** What every [[AntifraudEventRepository]] promises: the partitions it keeps (made on request, made again without error, dropped
  * when old, a write that finds none kept for the day's partition and moved into it when it is made), the batch it writes, the pruning of the log, and the rollup of the log into
  * per-minute statistics that runs once per interval. What it wrote is read back through [[AntifraudEventRepositorySpec.Fixtures]],
  * which the storage's runner implements; the storage adds in `implementationCases` what it alone promises (the names of its
  * partitions, a lock timeout, batched deletes, its indexes). */
trait AntifraudEventRepositorySpec extends DatabaseSpecBase[AntifraudEventRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import AntifraudEventRepositorySpec.*

  def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    val repository = env.repository
    val f = env.fixtures

    /** The days in [from, to] that have a partition, read through how many days after each have one. */
    def partitionDays(from: LocalDate, to: LocalDate): Task[List[LocalDate]] =
      val dates = LazyList.iterate(from.minusDays(1))(_.plusDays(1)).takeWhile(!_.isAfter(to)).toList
      ZIO.foreach(dates)(date => repository.partitionDaysAhead(date).map(date -> _)).map: counts =>
        counts.zip(counts.tail).collect { case ((_, before), (day, after)) if before > after => day }

    def partitions = partitionDays(today.minusDays(10), today.plusDays(10))

    def rollup(minute: Instant, interval: Duration): Task[Boolean] =
      repository.rollupStats(from = minute, to = minute.plusSeconds(60), prefixLength = 4, interval = interval)

    List(
      test("creates a partition for each requested day, and again without error") {
        for
          _ <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          _ <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          days <- partitions
        yield assertTrue(days == List(today, today.plusDays(1), today.plusDays(2)))
      },
      test("writes a batch in one go and reads every column back") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          first = event(utc(today.atTime(10, 0)))
          second = event(utc(today.atTime(11, 0)), AntifraudOutcome.suppressed).copy(
            tenantId = None,
            destPrefix = None,
            userAgent = None,
            decision = Some("Allow"),
            score = Some(75),
            rules = List("prefix_budget", "velocity"),
            wouldAction = Some("Fake"),
            asn = Some(24940),
            asnClass = Some("hosting"),
            country = Some("DE"),
          )
          _ <- repository.insertBatch(List(first, second))
          rows <- f.rows
        yield assertTrue(
          rows.map(_.id) == List(first.id, second.id),
          rows.map(_.tenantId) == List(Some("tenant-a"), None),
          rows.map(_.outcome) == List("sent", "suppressed"),
          rows.map(_.destPrefix) == List(Some("491512"), None),
          rows.map(_.userAgent) == List(Some("Mozilla/5.0"), None),
          rows.map(_.decision) == List(None, Some("Allow")),
          // What the engine decided, which in shadow mode is not what was done.
          rows.map(_.wouldAction) == List(None, Some("Fake")),
          rows.map(_.score) == List(None, Some(75)),
          rows.map(_.rules) == List("", "prefix_budget,velocity"),
          // An event with no ASN (the database is off, or the address is in no AS) keeps both columns empty.
          rows.map(_.asn) == List(None, Some(24940)),
          rows.map(_.asnClass) == List(None, Some("hosting")),
          // The country is as optional: neither database may be there, or know the address.
          rows.map(_.country) == List(None, Some("DE")),
        )
      },
      test("deletes the rows older than the limit from the day it falls in, to the minute, and leaves the newer ones and other days") {
        for
          _ <- repository.maintainPartitions(from = today.minusDays(1), days = 3, keepFrom = today.minusDays(30))
          limit = utc(today.atTime(10, 0))
          old = event(utc(today.atTime(9, 59)))
          atLimit = event(limit)
          newer = event(utc(today.atTime(10, 1)))
          // A row of the day before is a partition's to drop, not this method's.
          dayBefore = event(utc(today.minusDays(1).atTime(23, 0)))
          _ <- repository.insertBatch(List(old, atLimit, newer, dayBefore))
          _ <- repository.pruneEvents(limit)
          left <- f.rows.map(_.map(_.id))
        yield assertTrue(left == List(dayBefore.id, atLimit.id, newer.id))
      },
      test("an event of a day with no partition is written, and moved into the day's partition when that is created") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          late = event(utc(today.plusDays(5).atTime(8, 0)))
          _ <- repository.insertBatch(List(late))
          held <- f.countUnpartitioned
          // A day without a partition is not a day ahead, however many events it holds.
          ahead <- repository.partitionDaysAhead(today)
          moved <- repository.maintainPartitions(from = today, days = 7, keepFrom = today.minusDays(30))
          heldAfter <- f.countUnpartitioned
          rows <- f.rows.map(_.map(_.id))
          again <- repository.maintainPartitions(from = today, days = 7, keepFrom = today.minusDays(30))
          partitionsAhead <- repository.partitionDaysAhead(today)
        yield assertTrue(
          held == 1L,
          ahead == 0,
          moved == 1L,
          heldAfter == 0L,
          rows == List(late.id),
          again == 0L,
          partitionsAhead == 6,
        )
      },
      test("counts the events it moves, over every day it creates, and leaves those of a day it does not create") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          events = List(
            event(utc(today.plusDays(1).atTime(9, 0))),
            event(utc(today.plusDays(1).atTime(9, 1))),
            event(utc(today.plusDays(2).atTime(9, 0))),
            event(utc(today.plusDays(30).atTime(9, 0))),
          )
          _ <- repository.insertBatch(events)
          moved <- repository.maintainPartitions(from = today, days = 4, keepFrom = today.minusDays(30))
          heldAfter <- f.countUnpartitioned
          rows <- f.rows.map(_.size)
        yield assertTrue(moved == 3L, heldAfter == 1L, rows == 4)
      },
      test("an event of a day gone by, written without a partition, stays where it is and does not stop the days after it") {
        for
          _ <- repository.insertBatch(List(event(utc(today.minusDays(2).atTime(9, 0)))))
          moved <- repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30))
          days <- partitions
          held <- f.countUnpartitioned
        yield assertTrue(moved == 0L, days.size == 3, held == 1L)
      },
      test("deletes what the day without a partition holds once it is older than the retention, whatever its day") {
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(30))
          limit = utc(today.minusDays(14).atTime(10, 0))
          old = event(utc(today.minusDays(20).atTime(9, 0)))
          justOld = event(utc(today.minusDays(14).atTime(9, 59)))
          kept = event(utc(today.minusDays(14).atTime(10, 1)))
          future = event(utc(today.plusDays(30).atTime(9, 0)))
          _ <- repository.insertBatch(List(old, justOld, kept, future))
          before <- f.countUnpartitioned
          _ <- repository.pruneEvents(limit)
          left <- f.rows.map(_.map(_.id).toSet)
        yield assertTrue(before == 4L, left == Set(kept.id, future.id))
      },
      test("drops the partitions of days older than the retention, and only those") {
        for
          _ <- repository.maintainPartitions(from = today.minusDays(3), days = 5, keepFrom = today.minusDays(30))
          _ <- repository.insertBatch(List(event(utc(today.minusDays(3).atTime(9, 0)))))
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = today.minusDays(1))
          days <- partitions
          rows <- f.countEvents
        yield assertTrue(
          days == List(today.minusDays(1), today, today.plusDays(1)),
          rows == 0L,
        )
      },
      test("two maintenance runs at once both succeed") {
        for
          results <- ZIO.foreachPar(1 to 4)(_ =>
            repository.maintainPartitions(from = today, days = 3, keepFrom = today.minusDays(30)).exit,
          )
          days <- partitions
        yield assertTrue(results.forall(_.isSuccess), days.size == 3)
      },
      test("rolls the log up per tenant, range and minute, again without double counting, and prunes by age") {
        val minute = Instant.parse("2026-10-21T10:05:00Z")
        def at(second: Long, outcome: AntifraudOutcome, action: AntifraudAction = AntifraudAction.otp_request, prefix: String = "491512") =
          event(minute.plusSeconds(second), outcome).copy(action = action, destPrefix = Some(prefix))
        for
          _ <- repository.maintainPartitions(from = today, days = 1, keepFrom = LocalDate.of(2026, 10, 1))
          _ <- f.clearStats
          _ <- repository.insertBatch(
            List(
              at(1, AntifraudOutcome.sent),
              at(2, AntifraudOutcome.sent, prefix = "491519"),
              at(3, AntifraudOutcome.throttled),
              at(4, AntifraudOutcome.verified, AntifraudAction.otp_verify),
              at(5, AntifraudOutcome.wrong_code, AntifraudAction.otp_verify),
              at(5, AntifraudOutcome.fake_verify, AntifraudAction.otp_verify),
              at(6, AntifraudOutcome.sent, prefix = "491612"),
              // the steps of a challenge are not requests of their own: the send that follows is the one counted
              at(8, AntifraudOutcome.challenged),
              at(9, AntifraudOutcome.challenge_failed),
              at(10, AntifraudOutcome.challenge_passed),
              // a code passed by passkey is no request, and converts the code that was sent as a verification does
              at(11, AntifraudOutcome.passkey_alternative, AntifraudAction.otp_verify),
              // an address, and a minute outside the window
              event(minute.plusSeconds(7), AntifraudOutcome.sent).copy(channel = AntifraudChannel.email, destPrefix = None),
              at(70, AntifraudOutcome.sent),
            ),
          )
          ran <- rollup(minute, Duration.Zero)
          again <- rollup(minute, Duration.Zero)
          stats <- f.stats
          _ <- repository.pruneStats(minute.plusSeconds(60))
          left <- f.stats
        yield assertTrue(
          ran,
          again,
          stats == List(
            StatRow(
              tenant = "tenant-a",
              prefix = "4915",
              minute = minute,
              requests = 3,
              sent = 2,
              throttled = 1,
              verified = 2,
              wrong = 1,
              fakeVerifies = 1,
            ),
            StatRow(
              tenant = "tenant-a",
              prefix = "4916",
              minute = minute,
              requests = 1,
              sent = 1,
              throttled = 0,
              verified = 0,
              wrong = 0,
              fakeVerifies = 0,
            ),
          ),
          left.isEmpty,
        )
      },
      test("two rollups at once: one does the work, the other leaves it to it") {
        val minute = Instant.parse("2026-10-21T10:05:00Z")
        for results <- ZIO.foreachPar(1 to 6)(_ => rollup(minute, Duration.Zero).exit)
        yield assertTrue(results.forall(_.isSuccess))
      },
      test("counts the days after today that have a partition") {
        for
          none <- repository.partitionDaysAhead(today)
          _ <- repository.maintainPartitions(from = today, days = 8, keepFrom = today.minusDays(30))
          ahead <- repository.partitionDaysAhead(today)
          later <- repository.partitionDaysAhead(today.plusDays(5))
        yield assertTrue(none == 0, ahead == 7, later == 2)
      },
      test("the rollup runs once per interval across instances: a run within half of one skips, and the marker is the database's") {
        val minute = Instant.parse("2026-10-21T10:05:00Z")
        for
          _ <- f.clearRollupState
          first <- rollup(minute, 1.minute)
          second <- rollup(minute, 1.minute)
          // Another instance with a shorter interval finds the marker old enough, and one with none at all always runs.
          other <- rollup(minute, 1.millis)
          again <- rollup(minute, Duration.Zero)
          // A marker that is 40 seconds old is older than half of a one-minute interval.
          _ <- f.ageRollupMarker(40.seconds)
          late <- rollup(minute, 1.minute)
        yield assertTrue(first, !second, other, again, late)
      },
    )

object AntifraudEventRepositorySpec:
  case class Env(repository: AntifraudEventRepository, fixtures: Fixtures)

  /** An event as stored, `rules` joined with commas. */
  case class EventRow(
      id: UUID,
      tenantId: Option[String],
      clientId: String,
      outcome: String,
      destPrefix: Option[String],
      userAgent: Option[String],
      decision: Option[String],
      score: Option[Int],
      rules: String,
      wouldAction: Option[String],
      asn: Option[Int],
      asnClass: Option[String],
      country: Option[String],
  )

  /** A row of the rollup. */
  case class StatRow(
      tenant: String,
      prefix: String,
      minute: Instant,
      requests: Int,
      sent: Int,
      throttled: Int,
      verified: Int,
      wrong: Int,
      fakeVerifies: Int,
  )

  /** What a case reads of what the repository wrote, and resets between steps: the storage's runner supplies it. */
  trait Fixtures:
    /** Every event in the log, oldest first. */
    def rows: Task[List[EventRow]]

    def countEvents: Task[Long]

    /** How many events are held for a day that has no partition (the default partition's rows). */
    def countUnpartitioned: Task[Long]

    /** Every row of the rollup, ordered by prefix. */
    def stats: Task[List[StatRow]]

    def clearStats: Task[Unit]

    /** Forgets when the rollup last ran, as if no instance had. */
    def clearRollupState: Task[Unit]

    /** Moves the marker of the last rollup `by` into the past, by the database's clock. */
    def ageRollupMarker(by: Duration): Task[Unit]

  val today: LocalDate = LocalDate.of(2026, 10, 21)
  val farFuture: LocalDate = LocalDate.of(2099, 1, 1)

  def utc(time: LocalDateTime): Instant = time.toInstant(ZoneOffset.UTC)

  def event(at: Instant, outcome: AntifraudOutcome = AntifraudOutcome.sent): AntifraudEvent = AntifraudEvent(
    id = UUID.randomUUID(),
    at = at,
    tenantId = Some(TenantId("tenant-a")),
    clientId = ClientId("client-a"),
    conversationId = UUID.randomUUID(),
    action = AntifraudAction.otp_request,
    outcome = outcome,
    channel = AntifraudChannel.sms,
    subjectHash = "hash-1",
    destPrefix = Some("491512"),
    userAgent = Some("Mozilla/5.0"),
    score = None,
    rules = Nil,
  )
