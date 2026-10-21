package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.{ClientId, TenantId}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object PostgresAntifraudEventRepositorySpec extends PostgresSpec:

  private case class Row(
      id: UUID,
      tenantId: Option[String],
      clientId: String,
      outcome: String,
      destPrefix: Option[String],
      userAgent: Option[String],
      decision: Option[String],
      score: Option[Int],
      rules: String,
  ) derives DbCodec

  private val today = LocalDate.of(2026, 10, 21)
  private val farFuture = LocalDate.of(2099, 1, 1)

  private def event(at: Instant, outcome: AntifraudOutcome = AntifraudOutcome.sent) = AntifraudEvent(
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
  )

  private def partitions(xa: TransactorZIO): Task[List[String]] =
    xa.connect:
      sql"""SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'af_event' ORDER BY c.relname""".query[String].run().toList

  private def count(xa: TransactorZIO): Task[Long] =
    xa.connect(sql"SELECT COUNT(*) FROM af_event".query[Long].run().head)

  private def noPartitions: ZIO[TransactorZIO, Throwable, PostgresAntifraudEventRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      repository = PostgresAntifraudEventRepository(xa)
      // Everything older than a date far ahead goes, partitions this suite made included.
      _ <- repository.maintainPartitions(farFuture, 0, farFuture.plusYears(1))
    yield repository

  def spec = suite("PostgresAntifraudEventRepository")(
    test("creates a partition for each requested day, and again without error") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 3, today.minusDays(30))
        _ <- repository.maintainPartitions(today, 3, today.minusDays(30))
        names <- partitions(xa)
      yield assertTrue(names == List("af_event_20261021", "af_event_20261022", "af_event_20261023"))
    },
    test("writes a batch in one go and reads every column back") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 1, today.minusDays(30))
        first = event(today.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC))
        second = event(today.atTime(11, 0).toInstant(java.time.ZoneOffset.UTC), AntifraudOutcome.suppressed).copy(
          tenantId = None,
          destPrefix = None,
          userAgent = None,
          decision = Some("fake"),
          score = Some(75),
          rules = List("prefix_budget", "velocity"),
        )
        _ <- repository.insertBatch(List(first, second))
        rows <- xa.connect:
          sql"SELECT id, tenant_id, client_id, outcome, dest_prefix, user_agent, decision, score, array_to_string(rules, ',') FROM af_event ORDER BY at"
            .query[Row].run().toList
      yield assertTrue(
        rows.map(_.id) == List(first.id, second.id),
        rows.map(_.tenantId) == List(Some("tenant-a"), None),
        rows.map(_.outcome) == List("sent", "suppressed"),
        rows.map(_.destPrefix) == List(Some("491512"), None),
        rows.map(_.userAgent) == List(Some("Mozilla/5.0"), None),
        rows.map(_.decision) == List(None, Some("fake")),
        rows.map(_.score) == List(None, Some(75)),
        rows.map(_.rules) == List("", "prefix_budget,velocity"),
      )
    },
    test("an event of a day with no partition fails the write, so a late maintenance run is visible") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today, 1, today.minusDays(30))
        result <- repository.insertBatch(List(event(today.plusDays(5).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)))).exit
        rows <- count(xa)
      yield assertTrue(result.isFailure, rows == 0L)
    },
    test("drops the partitions of days older than the retention, and only those") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- repository.maintainPartitions(today.minusDays(3), 5, today.minusDays(30))
        _ <- repository.insertBatch(List(event(today.minusDays(3).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC))))
        _ <- repository.maintainPartitions(today, 1, today.minusDays(1))
        names <- partitions(xa)
        rows <- count(xa)
      yield assertTrue(
        names == List("af_event_20261020", "af_event_20261021", "af_event_20261022"),
        rows == 0L,
      )
    },
    test("leaves a table that is not one of its partitions alone") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        _ <- xa.connect(sql"CREATE TABLE af_event_20200101_old PARTITION OF af_event FOR VALUES FROM ('2020-01-01 00:00:00+00') TO ('2020-01-02 00:00:00+00')".update.run())
        _ <- repository.maintainPartitions(today, 1, today)
        names <- partitions(xa)
        _ <- xa.connect(sql"DROP TABLE af_event_20200101_old".update.run())
      yield assertTrue(names.contains("af_event_20200101_old"))
    },
    test("two maintenance runs at once both succeed") {
      for
        repository <- noPartitions
        xa <- ZIO.service[TransactorZIO]
        results <- ZIO.foreachPar(1 to 4)(_ => repository.maintainPartitions(today, 3, today.minusDays(30)).exit)
        names <- partitions(xa)
      yield assertTrue(results.forall(_.isSuccess), names.size == 3)
    },
    test("names and reads partitions by day") {
      assertTrue(
        PostgresAntifraudEventRepository.partitionName(today) == "af_event_20261021",
        PostgresAntifraudEventRepository.partitionDay("af_event_20261021") == Some(today),
        PostgresAntifraudEventRepository.partitionDay("af_event_20261321").isEmpty,
        PostgresAntifraudEventRepository.partitionDay("af_event_default").isEmpty,
        PostgresAntifraudEventRepository.partitionDay("conversations_20261021").isEmpty,
      )
    },
  ) @@ TestAspect.sequential
