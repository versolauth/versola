package versola.util.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import io.opentelemetry.api
import versola.util.SecureRandom
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.postgres.{AuditInsert, AuditTestTable, PostgresSpec}
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.time.{Instant, LocalDate}

/** The writer against a real Postgres: how long one of its transactions is open while a batch is written, and what chunking costs on a
  * database that is not slow. The driver sends a batch as one statement per row inside one transaction, so a batch of `n` rows at
  * `millis` a row holds its transaction for `n * millis`, whatever `statement_timeout` is. Real clock; the bounds are derived from the
  * cost of a row measured on the machine the spec runs on. */
object PostgresAuditChunkSpec extends PostgresSpec:

  private val Table = "chunk_spec_audit_event"

  private val table = AuditTestTable("audit_chunk_spec", Table)

  private val marker = "audit_chunk_spec"

  private val kind = AuditKind("thing.done")

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** The repository of the copy, its transactions marked so that the spec can find them in `pg_stat_activity`. */
  private class CopyRepository(xa: TransactorZIO) extends AuditEventRepository:
    override def insertBatch(events: List[AuditEvent]): Task[Unit] =
      table.inTransaction(xa):
        sql"SELECT set_config('application_name', $marker, true)".query[String].run()
        AuditInsert.insertBatch(events, table = Table)

    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] =
      ZIO.succeed(PartitionReport(movedFromDefault = 0L, daysLeftInDefault = Nil, created = 0, dropped = 0))

    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)

    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)

    override def ownership: Task[TableOwnership] = ZIO.succeed(TableOwnership(owner = "dev", role = "dev", mayAlter = true))

  private val settings = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)

  private def sizing(chunk: Int) =
    AuditEvents.Sizing(
      queueCapacity = 50000,
      batchSize = 1000,
      writeChunk = chunk,
      flushInterval = 1.second,
      partitionsAhead = 3,
      maintenanceInterval = 1.hour,
    )

  private def writer(xa: TransactorZIO, chunk: Int): ZIO[Scope, Nothing, AuditEvents] =
    AuditEvents
      .liveWith(sizing(chunk))(AuditSource("svc"), "node-1", AuditCatalog.of(Set(kind)), Table)
      .build
      .map(_.get[AuditEvents])
      .provideSome[Scope](ZLayer.succeed[AuditEventRepository](CopyRepository(xa)), ZLayer.succeed(settings), tracingLayer, SecureRandom.live)

  private def draft =
    AuditDraft(kind = kind, outcome = AuditOutcome.success, reason = None, actor = None, userId = None, target = None, details = AuditDetails.empty)

  private def stored(xa: TransactorZIO): Task[Long] = table.count(xa, Table)

  /** The `xact_start` (as epoch microseconds) and age in seconds of every open transaction of the writer. */
  private def transactions(xa: TransactorZIO): Task[List[(Long, Double)]] =
    table.inTransaction(xa):
      sql"""
        SELECT (EXTRACT(EPOCH FROM xact_start) * 1000000)::bigint, EXTRACT(EPOCH FROM (now() - xact_start))::float8
        FROM pg_stat_activity WHERE application_name = $marker AND xact_start IS NOT NULL
      """.query[(Long, Double)].run().toList

  private def event(): AuditEvent =
    AuditEvent(
      id = java.util.UUID.randomUUID(),
      at = Instant.parse("2026-11-04T10:00:00Z"),
      tenantId = None,
      source = AuditSource("svc"),
      nodeId = "node-1",
      kind = kind,
      outcome = AuditOutcome.success,
      reason = Some("calibration"),
      severity = AuditSeverity.info,
      actorType = ActorType.system,
      actorId = None,
      userId = None,
      clientId = None,
      sessionId = None,
      ip = None,
      userAgent = None,
      traceId = None,
      targetType = None,
      targetId = None,
      details = zio.json.ast.Json.Obj(),
    )

  /** The milliseconds one row costs on this machine, with the trigger: a hundred rows in a transaction of their own. */
  private def perRowMillis(xa: TransactorZIO): Task[Double] =
    for
      started <- Clock.nanoTime
      _ <- CopyRepository(xa).insertBatch(List.fill(100)(event()))
      finished <- Clock.nanoTime
    yield (finished - started) / 1e6 / 100

  private case class Run(
      perRow: Double,
      longestOpenSeconds: Double,
      transactionsSeen: Int,
      elapsedSeconds: Double,
      stored: Long,
  )

  /** One event, then `burst` more: the writer takes the first at once and the rest as one batch. The writer's open transactions are
    * sampled every 50 ms from the first record until everything is stored; a transaction is told apart by when it began. */
  private def slowBatch(xa: TransactorZIO, millis: Int, chunk: Int, burst: Int): Task[Run] =
    for
      _ <- table.slowInserts(xa, millis)
      perRow <- perRowMillis(xa)
      _ <- table.endSessions(xa, marker)
      seen <- Ref.make(Map.empty[Long, Double])
      scope <- Scope.make
      events <- scope.extend(writer(xa, chunk))
      calibrated <- stored(xa)
      started <- Clock.nanoTime
      sampler <-
        (ZIO.sleep(50.millis) *> transactions(xa).flatMap(open =>
          seen.update(known => open.foldLeft(known)((all, t) => all.updated(t._1, math.max(all.getOrElse(t._1, 0.0), t._2)))),
        ))
          .forever.fork
      _ <- events.record(draft).ignore
      _ <- ZIO.foreachDiscard(1 to burst)(_ => events.record(draft).ignore)
      total <- (ZIO.sleep(50.millis) *> stored(xa))
        .repeatUntil(_ - calibrated >= burst + 1L)
        .timeoutFail(RuntimeException(s"the events were not stored within 120 s"))(120.seconds)
      finished <- Clock.nanoTime
      _ <- sampler.interrupt
      open <- seen.get
      _ <- scope.close(Exit.unit)
    yield Run(
      perRow = perRow,
      longestOpenSeconds = open.values.maxOption.getOrElse(0.0),
      transactionsSeen = open.size,
      elapsedSeconds = (finished - started) / 1e9,
      stored = total - calibrated,
    )

  /** Rows a second the writer carries a backlog of `rows` at, on a database that is not slow, with chunks of `chunk`. */
  private def throughput(xa: TransactorZIO, chunk: Int, rows: Int): Task[Double] =
    for
      _ <- table.reset.provide(ZLayer.succeed(xa))
      scope <- Scope.make
      events <- scope.extend(writer(xa, chunk))
      started <- Clock.nanoTime
      _ <- ZIO.foreachDiscard(1 to rows)(_ => events.record(draft).ignore)
      _ <- (ZIO.sleep(20.millis) *> stored(xa)).repeatUntil(_ >= rows.toLong).timeoutFail(RuntimeException("not stored in time"))(120.seconds)
      finished <- Clock.nanoTime
      _ <- scope.close(Exit.unit)
    yield rows / ((finished - started) / 1e9)

  val spec = suite("the writer against Postgres")(
    test("a batch of 1000 rows at ten milliseconds a row is written in transactions that each stay open for about a chunk, not for the batch") {
      for
        xa <- ZIO.service[TransactorZIO]
        run <- slowBatch(xa, millis = 10, chunk = 100, burst = 1000)
        chunkSeconds = run.perRow * 100 / 1000
        batchSeconds = run.perRow * 1000 / 1000
        _ <- Console.printLine(
          f"slow batch: ${run.perRow}%.1f ms a row, longest transaction ${run.longestOpenSeconds}%.2f s of ${batchSeconds}%.2f s for the batch, ${run.transactionsSeen} transactions seen, ${run.elapsedSeconds}%.2f s in all",
        ).orDie
      yield assertTrue(
        run.stored == 1001L,
        // The batch alone would hold its transaction for `batchSeconds`; a chunk holds it for `chunkSeconds`, and the sampling and the
        // commit add a little.
        run.longestOpenSeconds < chunkSeconds * 1.5 + 0.5,
        run.longestOpenSeconds < batchSeconds / 2,
        // The writer was seen at work in many transactions, not in one: ten chunks of the batch, and the first row.
        run.transactionsSeen >= 6,
        // Chunking costs a commit a chunk and no more: the whole takes the rows' own time, the writer's rest after the first row,
        // and a margin.
        run.elapsedSeconds < batchSeconds * 1.25 + 1.0 + 1.5,
      )
    },
    test(
      "without a slow trigger the writer's rate with chunks of 100 is not pathologically below its rate with one transaction a batch (the rates are printed)",
    ) {
      for
        xa <- ZIO.service[TransactorZIO]
        whole <- throughput(xa, chunk = 1000, rows = 20000)
        chunked <- throughput(xa, chunk = 100, rows = 20000)
        _ <- Console.printLine(
          f"writer, 20000 rows in the queue: one transaction a batch of 1000: $whole%.0f rows/s; chunks of 100: $chunked%.0f rows/s",
        ).orDie
      yield // A guard against a chunk a row, not a benchmark: a machine under load moves the ratio, and the rates above are what is read.
        assertTrue(chunked >= whole * 0.3)
    },
  ).provideSomeLayer[TransactorZIO](ZTestLogger.default) @@ TestAspect.sequential @@ TestAspect.before(
    table.reset,
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(300.seconds)
