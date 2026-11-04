package versola.util.audit

import io.opentelemetry.api
import org.scalamock.stubs.Stub
import versola.util.http.{Observability, VersolaApp}
import versola.util.partitions.{PartitionReport, TableOwnership}
import versola.util.{SecureRandom, UnitSpecBase}
import zio.*
import zio.json.ast.Json
import zio.metrics.{Metric, MetricLabel, MetricState}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.sql.SQLException
import java.time.{Instant, LocalDate}
import java.util.UUID

object AuditEventsSpec extends UnitSpecBase:

  private val nodeId = "node-1"
  private val owned = TableOwnership(owner = "auth", role = "auth", mayAlter = true)
  private val noReport = PartitionReport(movedFromDefault = 0L, daysLeftInDefault = Nil, created = 0, dropped = 0)
  private val Issued = AuditKind("thing.issued")
  private val Refused = AuditKind("thing.refused")
  private val Changed = AuditKind("thing.changed")
  private val catalog = AuditCatalog.of(kinds = Set(Issued, Refused, Changed), high = Set(Changed))

  private val svcA = AuditSource("svc-a")
  private val central = AuditSource("svc-b")
  private val edge = AuditSource("svc-c")
  private val admin = ActorType("admin")

  private val userId = UUID.fromString("5f1c8f0e-6a3e-4a52-9d3e-2b1c0f6f3a01")

  private val block = AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds)
  private val drop = block.copy(overflow = AuditEvents.Overflow.drop)

  private val sizing =
    AuditEvents.Sizing(queueCapacity = 2, batchSize = 2, flushInterval = 1.second, partitionsAhead = 3, maintenanceInterval = 1.hour)

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private def draft(kind: AuditKind = Issued, outcome: AuditOutcome = AuditOutcome.success) =
    AuditDraft(kind = kind, outcome = outcome)

  /** A repository whose first `insertBatch` waits for `gate`, so that the writer is held with one batch in hand while the queue is
    * filled; every batch it was given, in order, is in `written`. `failures` is how many of the first calls fail. */
  private class Fixture(
      val repository: Stub[AuditEventRepository],
      val started: Queue[Unit],
      val gate: Promise[Nothing, Unit],
      val written: Ref[List[List[AuditEvent]]],
  )

  /** A repository that keeps what it is given, by id and once, as the table does with `ON CONFLICT (id, at) DO NOTHING`. The first
    * call can be held on `gate`, before it stores or after, and the repository can be made to fail. */
  private class Store(
      val stored: Ref[Map[UUID, AuditEvent]],
      val rewritten: Ref[Int],
      val entered: Queue[Unit],
      val gate: Promise[Nothing, Unit],
      val down: Ref[Boolean],
      calls: Ref[Int],
      holdFirst: Boolean,
      storeBeforeHold: Boolean,
      uninterruptible: Boolean,
      holdAll: Boolean,
  ) extends AuditEventRepository:

    override def insertBatch(events: List[AuditEvent]): Task[Unit] =
      for
        n <- calls.updateAndGet(_ + 1)
        _ <- entered.offer(())
        isDown <- down.get
        _ <- ZIO.fail(RuntimeException("connection refused")).when(isDown)
        // The transactor runs a statement uninterruptibly, so a stub that does the same is what a stop meets in a service.
        hold = (if uninterruptible then ZIO.uninterruptible(gate.await) else gate.await).when(holdAll || (holdFirst && n == 1))
        store = stored
          .modify(known => (events.count(e => known.contains(e.id)), known ++ events.filterNot(e => known.contains(e.id)).map(e => e.id -> e)))
          .flatMap(again => rewritten.update(_ + again))
        _ <- if storeBeforeHold then store *> hold else hold *> store
      yield ()

    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] = ZIO.succeed(noReport)

    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)

    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)

    override def ownership: Task[TableOwnership] = ZIO.succeed(owned)

  /** A repository whose statement holds a real thread of the blocking pool and is not cut by interrupting it, as a socket read of the
    * database driver is: it ends when `open` is set. */
  private class SpinningRepository(open: java.util.concurrent.atomic.AtomicBoolean, entered: Queue[Unit]) extends AuditEventRepository:
    override def insertBatch(events: List[AuditEvent]): Task[Unit] =
      entered.offer(()) *> ZIO.attemptBlocking(while !open.get() do Thread.sleep(5))

    override def maintainPartitions(from: LocalDate, days: Int, keepFrom: LocalDate): Task[PartitionReport] = ZIO.succeed(noReport)

    override def partitionDaysAhead(today: LocalDate): Task[Int] = ZIO.succeed(3)

    override def pruneDefault(before: Instant): Task[Long] = ZIO.succeed(0L)

    override def ownership: Task[TableOwnership] = ZIO.succeed(owned)

  private object Store:
    def make(
        holdFirst: Boolean = false,
        storeBeforeHold: Boolean = false,
        down: Boolean = false,
        uninterruptible: Boolean = false,
        holdAll: Boolean = false,
    ): UIO[Store] =
      for
        stored <- Ref.make(Map.empty[UUID, AuditEvent])
        rewritten <- Ref.make(0)
        entered <- Queue.unbounded[Unit]
        gate <- Promise.make[Nothing, Unit]
        isDown <- Ref.make(down)
        calls <- Ref.make(0)
      yield Store(stored, rewritten, entered, gate, isDown, calls, holdFirst, storeBeforeHold, uninterruptible, holdAll)

  private def fixture(gated: Boolean, failures: Int = 0, refuse: AuditEvent => Option[Throwable] = _ => None): UIO[Fixture] =
    for
      repository <- ZIO.succeed(stub[AuditEventRepository])
      started <- Queue.unbounded[Unit]
      gate <- Promise.make[Nothing, Unit]
      written <- Ref.make(List.empty[List[AuditEvent]])
      calls <- Ref.make(0)
      _ <- repository.insertBatch.returnsZIO: events =>
        calls.updateAndGet(_ + 1).flatMap: n =>
          started.offer(()) *>
            gate.await.when(gated && n == 1) *>
            (events.flatMap(refuse).headOption match
              case Some(error) => ZIO.fail(error)
              case None => if n <= failures then ZIO.fail(RuntimeException("connection refused")) else written.update(_ :+ events))
      _ <- repository.ownership.succeedsWith(owned)
      _ <- repository.maintainPartitions.succeedsWith(noReport)
      _ <- repository.partitionDaysAhead.succeedsWith(3)
      _ <- repository.pruneDefault.succeedsWith(0L)
    yield Fixture(repository, started, gate, written)

  private def build(
      f: Fixture,
      settings: AuditEvents.Settings,
      source: AuditSource = svcA,
      sizing: AuditEvents.Sizing = sizing,
  ): ZIO[Scope, Nothing, AuditEvents] = buildOn(f.repository, settings, source, sizing)

  private def buildOn(
      repository: AuditEventRepository,
      settings: AuditEvents.Settings,
      source: AuditSource = svcA,
      sizing: AuditEvents.Sizing = sizing,
  ): ZIO[Scope, Nothing, AuditEvents] =
    AuditEvents.liveWith(sizing)(source, nodeId, catalog).build.map(_.get[AuditEvents]).provideSome[Scope](
      ZLayer.succeed[AuditEventRepository](repository),
      ZLayer.succeed(settings),
      tracingLayer,
      SecureRandom.live,
    )

  /** A scope closed as the app closes its own: `ZIO.scoped` runs its release uninterruptibly, so a finalizer cannot rely on a timeout to
    * interrupt what it waits for. A spec that closes the scope from an ordinary fiber would not see that. */
  private def closedAsTheAppDoes(scope: Scope.Closeable): UIO[Unit] = ZIO.uninterruptible(scope.close(Exit.unit))

  /** Closes a scope whose repository may keep failing: the stop retries until the drain timeout, which only the clock can end. */
  private def stopped(scope: Scope.Closeable): UIO[Unit] =
    closedAsTheAppDoes(scope).fork.flatMap(closing => settle *> TestClock.adjust(AuditEvents.DrainTimeout.plus(2.seconds)) *> settle *> closing.join)

  /** The lines the log wrote about events it dropped or refused. */
  private def reportLines: UIO[List[String]] =
    // The fiber a clock move wakes writes its line some yields later.
    (settle *> ZTestLogger.logOutput).map(_.toList.map(_.message()).filter(_.contains("audit events were dropped or refused")))

  private def drainLines: UIO[List[String]] =
    (settle *> ZTestLogger.logOutput).map(_.toList.map(_.message()).filter(_.contains("the audit queue was drained")))

  private def counter(name: String, source: AuditSource): UIO[Double] =
    Metric.counter(name).tagged(Set(MetricLabel("source", source.value))).value.map(_.count)

  private def gauge(name: String, source: AuditSource): UIO[Double] =
    Metric.gauge(name).tagged(Set(MetricLabel("source", source.value))).value.map(_.value)

  /** Reads until the condition holds. A fiber that a clock move woke, or a write the writer made, runs when the scheduler gets to it,
    * and a count of yields says nothing of when; a test that waits for a positive state waits on that state. */
  private def eventually[A](read: UIO[A])(holds: A => Boolean): UIO[A] = (ZIO.yieldNow *> read).repeatUntil(holds)

  /** Lets the fibers the test forked run: under the test clock a sleep ends only when the clock moves. */
  private def settle = ZIO.yieldNow.repeatN(20)

  /** The batches written once there are `n` of them: the writer rests a second after a batch that did not fill, which only the
    * test clock can end. */
  private def until(f: Fixture, n: Int): UIO[List[List[AuditEvent]]] =
    (settle *> TestClock.adjust(1.second) *> f.written.get).repeatUntil(_.size >= n)

  val spec = suite("AuditEvents")(
    suite("record")(
      test("writes the event filled in from the draft and the request's context") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            _ <- TestClock.setTime(Instant.parse("2026-11-03T10:00:00Z"))
            events <- build(f, block)
            _ <- Observability.setUserId(userId.toString) *> Observability.setIp("203.0.113.7") *> Observability.setClientId("client-a")
            _ <- AuditContext.setTenant("tenant-a") *> AuditContext.setUserAgent("Firefox 130 / macOS")
            _ <- AuditContext.setTarget("client", "client-a") *> AuditContext.setChanged(Set("redirect_uris", "name"))
            _ <- events.record(AuditDraft(kind = Refused, outcome = AuditOutcome.failure, reason = Some("invalid_grant")))
            written <- until(f, 1)
          yield
            val event = written.flatten.head
            assertTrue(
              written.size == 1,
              event.at == Instant.parse("2026-11-03T10:00:00Z"),
              event.source == svcA,
              event.nodeId == nodeId,
              event.kind == Refused,
              event.outcome == AuditOutcome.failure,
              event.reason == Some("invalid_grant"),
              event.severity == AuditSeverity.notice,
              event.actorType == ActorType.user,
              event.actorId == Some(userId.toString),
              event.userId == Some(userId),
              event.clientId == Some("client-a"),
              event.ip == Some("203.0.113.7"),
              event.tenantId == Some("tenant-a"),
              event.userAgent == Some("Firefox 130 / macOS"),
              event.targetType == Some("client"),
              event.targetId == Some("client-a"),
              event.traceId.isEmpty,
              // What the request noted it changed goes with the event.
              event.details == Json.Obj("changed" -> Json.Arr(Json.Str("name"), Json.Str("redirect_uris"))),
            )
      },
      test("an event with no user in the request is the system's, and the draft's own actor, user and target win over the context") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block)
            _ <- events.record(draft())
            _ <- until(f, 1)
            _ <- AuditContext.setTarget("client", "from-context")
            _ <- events.record(
              AuditDraft(
                kind = Changed,
                outcome = AuditOutcome.success,
                actor = Some((admin, Some("admin-1"))),
                userId = Some(userId),
                target = Some(("client", "from-draft")),
              ),
            )
            written <- until(f, 2).map(_.flatten)
          yield assertTrue(
            written.map(_.actorType) == List(ActorType.system, admin),
            written.map(_.actorId) == List(None, Some("admin-1")),
            written.map(_.userId) == List(None, Some(userId)),
            written.map(_.targetId) == List(None, Some("from-draft")),
          )
      },
      test("a draft's own `changed` is not overwritten by the context's") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block)
            _ <- AuditContext.setChanged(Set("name"))
            own = new AuditDetails { def toJson = Json.Obj("changed" -> Json.Str("own")) }
            _ <- events.record(draft().copy(details = own))
            written <- until(f, 1).map(_.flatten)
          yield assertTrue(written.map(_.details) == List(Json.Obj("changed" -> Json.Str("own"))))
      },
      test("every event has an id of its own, in time order") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block, sizing = sizing.copy(queueCapacity = 10, batchSize = 10))
            _ <- ZIO.foreachDiscard(1 to 5)(_ => events.record(draft()))
            written <- until(f, 1).map(_.flatten)
          yield assertTrue(written.map(_.id).distinct.size == written.size, written.nonEmpty)
      },
      test("a batch never has more than batchSize events, and the writer goes on with the backlog without resting") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, block, sizing = sizing.copy(queueCapacity = 10, batchSize = 2))
            // The first is in the writer's hand, held by the gate; the rest gather behind it.
            _ <- events.record(draft())
            _ <- f.started.take
            _ <- ZIO.foreachDiscard(1 to 5)(_ => events.record(draft()))
            _ <- f.gate.succeed(())
            written <- until(f, 4)
          yield assertTrue(written.map(_.size) == List(1, 2, 2, 1))
      },
      test("rates the event with the service's catalog") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block, sizing = sizing.copy(queueCapacity = 10, batchSize = 10))
            _ <- events.record(draft(Changed, AuditOutcome.success))
            _ <- events.record(draft(Issued, AuditOutcome.success))
            _ <- events.record(draft(Refused, AuditOutcome.denied))
            written <- until(f, 1).map(_.flatten)
          yield assertTrue(written.map(_.severity) == List(AuditSeverity.high, AuditSeverity.info, AuditSeverity.notice))
      },
      test("a kind the service did not list is recorded, but counted under the label `other`, so a built kind cannot grow the label") {
        val labels = (kind: String) => Set(MetricLabel("source", "svc-c"), MetricLabel("kind", kind), MetricLabel("outcome", "success"))
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block, source = edge)
            otherBefore <- Metric.counter("audit_events_total").tagged(labels("other")).value.map(_.count)
            builtBefore <- Metric.counter("audit_events_total").tagged(labels("thing.built.by.a.request")).value.map(_.count)
            _ <- events.record(draft(AuditKind("thing.built.by.a.request")))
            otherAfter <- Metric.counter("audit_events_total").tagged(labels("other")).value.map(_.count)
            builtAfter <- Metric.counter("audit_events_total").tagged(labels("thing.built.by.a.request")).value.map(_.count)
            written <- until(f, 1).map(_.flatten)
          yield assertTrue(otherAfter - otherBefore == 1.0, builtAfter == builtBefore, written.map(_.kind.value) == List("thing.built.by.a.request"))
      },
      test("counts the event by kind and outcome") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block, source = edge)
            before <- Metric.counter("audit_events_total").tagged(
              Set(MetricLabel("source", "svc-c"), MetricLabel("kind", "thing.refused"), MetricLabel("outcome", "failure")),
            ).value.map(_.count)
            _ <- events.record(draft(Refused, AuditOutcome.failure))
            after <- Metric.counter("audit_events_total").tagged(
              Set(MetricLabel("source", "svc-c"), MetricLabel("kind", "thing.refused"), MetricLabel("outcome", "failure")),
            ).value.map(_.count)
          yield assertTrue(after - before == 1.0)
      },
    ),
    suite("overflow = block")(
      test("waits for room and records the event when the writer catches up in time") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, block, source = central)
            blockedBefore <- counter("audit_events_blocked_total", central)
            _ <- events.record(draft())
            _ <- f.started.take
            // The writer holds the first; the queue (two) is filled by the next two; the fourth has to wait.
            _ <- events.record(draft()) *> events.record(draft())
            waiting <- events.record(draft()).fork
            _ <- settle
            stillWaiting <- waiting.poll
            _ <- f.gate.succeed(())
            _ <- settle
            // The writer rests a second after the batch it was holding, then takes two: there is room.
            _ <- TestClock.adjust(1.second)
            result <- waiting.join.exit
            blockedAfter <- counter("audit_events_blocked_total", central)
          yield assertTrue(stillWaiting.isEmpty, result.isSuccess, blockedAfter - blockedBefore == 1.0)
      },
      test("fails the request with AuditUnavailable once the queue has stayed full for blockTimeout, and counts it") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, block, source = central)
            unavailableBefore <- counter("audit_events_unavailable_total", central)
            _ <- events.record(draft())
            _ <- f.started.take
            _ <- events.record(draft()) *> events.record(draft())
            waiting <- events.record(draft()).exit.fork
            _ <- settle
            early <- TestClock.adjust(1.second) *> waiting.poll
            _ <- TestClock.adjust(1.second)
            result <- waiting.join
            unavailableAfter <- counter("audit_events_unavailable_total", central)
          yield assertTrue(early.isEmpty, result == Exit.fail(AuditUnavailable), unavailableAfter - unavailableBefore == 1.0)
      },
      test("recordBestEffort does not fail when the queue stays full: the event is dropped and counted") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, block, source = central)
            droppedBefore <- counter("audit_events_dropped_total", central)
            _ <- events.record(draft())
            _ <- f.started.take
            _ <- events.record(draft()) *> events.record(draft())
            waiting <- events.recordBestEffort(draft()).fork
            _ <- settle
            _ <- TestClock.adjust(2.seconds)
            _ <- waiting.join
            droppedAfter <- counter("audit_events_dropped_total", central)
          yield assertTrue(droppedAfter - droppedBefore == 1.0)
      },
      test("a write that fails is kept and tried again every interval, until it is written, with the same events") {
        ZIO.scoped:
          for
            f <- fixture(gated = false, failures = 2)
            events <- build(f, block, sizing = sizing.copy(queueCapacity = 10))
            _ <- events.record(draft())
            _ <- f.started.take
            _ <- settle
            afterFirst <- f.written.get
            _ <- TestClock.adjust(1.second)
            _ <- f.started.take
            _ <- TestClock.adjust(1.second)
            _ <- f.started.take
            written <- eventually(f.written.get)(_.nonEmpty)
          yield assertTrue(afterFirst.isEmpty, written.size == 1, written.head.size == 1)
      },
    ),
    suite("a row the table cannot store")(
      test("is dropped and counted after the batch it was in is refused, and the rest of the batch is stored") {
        val poison = (event: AuditEvent) => Option.when(event.reason.contains("poison"))(SQLException("invalid input syntax for type inet", "22P02"))
        ZIO.scoped:
          for
            f <- fixture(gated = false, refuse = poison)
            events <- build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10))
            rejectedBefore <- counter("audit_events_rejected_total", edge)
            _ <- events.record(draft())
            _ <- events.record(draft().copy(reason = Some("poison")))
            _ <- events.record(draft(Refused, AuditOutcome.failure))
            // The batch is refused three times, a second apart, and then written row by row.
            written <- until(f, 2).map(_.flatten)
            rejectedAfter <- counter("audit_events_rejected_total", edge)
          yield assertTrue(
            written.map(_.kind) == List(Issued, Refused),
            !written.exists(_.reason.contains("poison")),
            rejectedAfter - rejectedBefore == 1.0,
          )
      },
      test("a constraint violation is dropped too, and the writer goes on with what is queued after it") {
        val poison = (event: AuditEvent) => Option.when(event.reason.contains("poison"))(SQLException("null value", "23502"))
        ZIO.scoped:
          for
            f <- fixture(gated = false, refuse = poison)
            events <- build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10))
            _ <- events.record(draft().copy(reason = Some("poison")))
            // The refused batch is tried three times a second apart, then the row alone, and dropped.
            _ <- settle *> TestClock.adjust(10.seconds)
            _ <- events.record(draft(Refused, AuditOutcome.failure))
            written <- until(f, 1).map(_.flatten)
          yield assertTrue(written.map(_.kind) == List(Refused))
      },
      test("is not dropped when the failure is the database's: a lone row that fails with no SQLSTATE is tried again until it is stored") {
        ZIO.scoped:
          for
            f <- fixture(gated = false, failures = 5)
            events <- build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10))
            rejectedBefore <- counter("audit_events_rejected_total", edge)
            _ <- events.record(draft())
            written <- until(f, 1)
            rejectedAfter <- counter("audit_events_rejected_total", edge)
          yield assertTrue(written.map(_.size) == List(1), rejectedAfter == rejectedBefore)
      },
      test("is not dropped when every row of the batch fails for the database's reason, a connection class SQLSTATE included") {
        val down = (_: AuditEvent) => Some(SQLException("the connection is closed", "08006"))
        for
          f <- fixture(gated = false, refuse = down)
          scope <- Scope.make
          events <- scope.extend(build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10)))
          rejectedBefore <- counter("audit_events_rejected_total", edge)
          _ <- events.record(draft()) *> events.record(draft())
          _ <- settle *> TestClock.adjust(1.minute).repeatN(3)
          written <- f.written.get
          rejectedAfter <- counter("audit_events_rejected_total", edge)
          // The stop retries until the drain timeout; the clock has to move for it to end.
          closing <- closedAsTheAppDoes(scope).fork
          _ <- settle *> TestClock.adjust(AuditEvents.DrainTimeout.plus(1.second)) *> settle
          _ <- closing.join
        yield assertTrue(written.isEmpty, rejectedAfter == rejectedBefore)
      },
      test("a draft never produces one: U+0000 is stripped, an address that is none is dropped, a user agent is cut") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block)
            _ <- Observability.setIp("not an address") *> Observability.setClientId("cli\u0000ent")
            _ <- AuditContext.setUserAgent("a" * 600) *> AuditContext.setTenant("ten\u0000ant")
            _ <- events.record(draft().copy(reason = Some("re\u0000ason")))
            written <- until(f, 1).map(_.flatten)
          yield assertTrue(
            written.map(_.ip) == List(None),
            written.map(_.clientId) == List(Some("client")),
            written.map(_.tenantId) == List(Some("tenant")),
            written.map(_.reason) == List(Some("reason")),
            written.map(_.userAgent.map(_.length)) == List(Some(AuditSanitizer.MaxUserAgent)),
          )
      },
    ),
    suite("events the log dropped or refused")(
      test("events dropped at a full queue are reported once per window, by kind, and never by content") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, drop, source = edge)
            _ <- events.record(draft(Issued, AuditOutcome.success))
            _ <- f.started.take
            // Two are queued behind the one the writer holds, three are dropped; then two of a kind the service did not list.
            _ <- ZIO.foreachDiscard(1 to 5)(_ => events.record(draft(Issued, AuditOutcome.success).copy(reason = Some("secret-detail"))))
            _ <- ZIO.foreachDiscard(1 to 2)(_ => events.record(draft(AuditKind("thing.unlisted"))))
            before <- reportLines
            _ <- TestClock.adjust(1.minute)
            lines <- eventually(reportLines)(_.nonEmpty)
            _ <- TestClock.adjust(2.minutes)
            later <- reportLines
          yield assertTrue(
            before.isEmpty,
            lines.size == 1,
            lines.head.contains("5 dropped, the queue was full: thing.issued (3), other (2)"),
            lines.head.contains("(svc-c)"),
            !lines.head.contains("secret-detail"),
            // A window with nothing to report says nothing.
            later == lines,
          )
      }.provideLayer(ZTestLogger.default),
      test("events a best-effort record gave up waiting for are reported the same way") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, block, source = edge)
            _ <- events.record(draft())
            _ <- f.started.take
            _ <- events.record(draft()) *> events.record(draft())
            waiting <- events.recordBestEffort(draft(Refused, AuditOutcome.failure)).fork
            _ <- settle
            _ <- TestClock.adjust(2.seconds)
            _ <- waiting.join
            _ <- TestClock.adjust(1.minute)
            lines <- eventually(reportLines)(_.nonEmpty)
          yield assertTrue(lines.size == 1, lines.head.contains("1 dropped, the queue stayed full for the block timeout: thing.refused (1)"))
      }.provideLayer(ZTestLogger.default),
      test("rows the database refused are reported once per window by kind with their SQLSTATE, and never by content") {
        val poison = (event: AuditEvent) =>
          Option.when(event.reason.contains("poison-detail"))(SQLException("invalid input syntax for type inet: \"poison-detail\"", "22P02"))
        ZIO.scoped:
          for
            f <- fixture(gated = false, refuse = poison)
            events <- build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10))
            _ <- events.record(draft())
            _ <- events.record(draft().copy(reason = Some("poison-detail")))
            // The batch is refused, then written row by row: the good row is stored, the refused one is not.
            _ <- until(f, 1)
            _ <- TestClock.adjust(1.minute)
            lines <- eventually(reportLines)(_.nonEmpty)
          yield assertTrue(
            lines.size == 1,
            lines.head.contains("1 refused by the database for what they contain, not stored: thing.issued (1); SQLSTATE 22P02"),
            !lines.head.contains("poison-detail"),
            !lines.head.contains("invalid input syntax"),
          )
      }.provideLayer(ZTestLogger.default),
      test("a shutdown says the queue was drained, and an empty queue says it too") {
        for
          f <- fixture(gated = false)
          _ <- ZIO.scoped(build(f, block, source = edge).unit)
          lines <- drainLines
        yield assertTrue(lines.size == 1, lines.head.contains("0 events taken, 0 not written"))
      }.provideLayer(ZTestLogger.default),
      test("the batch in hand and what is queued at shutdown are written, and the drain line says how many") {
        for
          f <- fixture(gated = true)
          _ <- ZIO.scoped:
            for
              events <- build(f, block, source = edge)
              _ <- events.record(draft())
              _ <- f.started.take
              _ <- events.record(draft()) *> events.record(draft())
            yield ()
          lines <- drainLines
          written <- f.written.get
        yield assertTrue(lines.size == 1, lines.head.contains("3 events taken, 0 not written"), written.flatten.size == 3)
      }.provideLayer(ZTestLogger.default),
      test("what cannot be written before the drain timeout is counted and reported by kind when the service stops, the batch in hand included") {
        for
          f <- fixture(gated = true, failures = 100)
          failedBefore <- counter("audit_events_failed_total", edge)
          scope <- Scope.make
          events <- scope.extend(build(f, block, source = edge))
          _ <- events.record(draft())
          _ <- f.started.take
          _ <- events.record(draft(Refused, AuditOutcome.failure)) *> events.record(draft(Refused, AuditOutcome.failure))
          closing <- closedAsTheAppDoes(scope).fork
          _ <- settle *> TestClock.adjust(AuditEvents.DrainTimeout.plus(1.second)) *> settle
          _ <- closing.join
          failedAfter <- counter("audit_events_failed_total", edge)
          drained <- drainLines
          reported <- eventually(reportLines)(_.nonEmpty)
        yield assertTrue(
          failedAfter - failedBefore == 3.0,
          drained.size == 1,
          drained.head.contains("3 events taken, 3 not written"),
          reported.size == 1,
          reported.head.contains("3 not written before the service stopped: thing.refused (2), thing.issued (1)"),
        )
      }.provideLayer(ZTestLogger.default),
      test("the queue's size is read while the writer is held by a database that is down, not only when it takes something") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, block, source = edge)
            _ <- events.record(draft())
            _ <- f.started.take
            _ <- events.record(draft()) *> events.record(draft())
            _ <- settle *> TestClock.adjust(2.seconds) *> settle
            size <- eventually(gauge("audit_queue_size", edge))(_ == 2.0)
          yield assertTrue(size == 2.0)
      },
    ),
    suite("a stop of the service")(
      test("while the repository is blocked inside insertBatch: every event the queue accepted is stored, none twice") {
        for
          store <- Store.make(holdFirst = true)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge))
          _ <- events.record(draft())
          _ <- store.entered.take
          _ <- events.record(draft()) *> events.record(draft())
          _ <- closedAsTheAppDoes(scope)
          stored <- store.stored.get
          again <- store.rewritten.get
        yield assertTrue(stored.size == 3, again == 0)
      },
      test("while the statement in the repository has stored its rows and not yet returned: every event is stored once") {
        for
          store <- Store.make(holdFirst = true, storeBeforeHold = true)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge))
          _ <- events.record(draft())
          _ <- store.entered.take
          _ <- settle
          _ <- events.record(draft()) *> events.record(draft())
          _ <- closedAsTheAppDoes(scope)
          stored <- store.stored.get
        yield assertTrue(stored.size == 3)
      },
      test("with producers running while it closes: every event a record returned for is stored, and one refused is not silently gone") {
        for
          store <- Store.make()
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge, sizing = sizing.copy(queueCapacity = 100000, batchSize = 100000)))
          accepted <- Ref.make(Set.empty[String])
          stop <- Ref.make(false)
          producer = (id: Int) =>
            (for
              n <- Ref.make(0)
              _ <- (for
                k <- n.updateAndGet(_ + 1)
                reason = s"p$id-$k"
                outcome <- events.record(draft().copy(reason = Some(reason))).exit
                _ <- accepted.update(_ + reason).when(outcome.isSuccess)
                _ <- ZIO.yieldNow
              yield ()).repeatUntilZIO(_ => stop.get)
            yield ()).fork
          producers <- ZIO.foreach(1 to 4)(producer)
          _ <- accepted.get.repeatUntil(_.size >= 50)
          _ <- closedAsTheAppDoes(scope)
          _ <- settle
          _ <- stop.set(true)
          _ <- ZIO.foreachDiscard(producers)(_.join)
          taken <- accepted.get
          stored <- store.stored.get.map(_.values.flatMap(_.reason).toSet)
        yield assertTrue(taken.nonEmpty, taken.subsetOf(stored))
      },
      test("during a backoff, when the repository recovers inside the drain timeout: the held rows are stored once") {
        for
          store <- Store.make(down = true)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10)))
          _ <- events.record(draft()) *> events.record(draft())
          _ <- store.entered.take
          _ <- settle
          closing <- closedAsTheAppDoes(scope).fork
          _ <- store.down.set(false)
          _ <- settle *> TestClock.adjust(2.seconds) *> settle
          _ <- closing.join
          stored <- store.stored.get
          again <- store.rewritten.get
        yield assertTrue(stored.size == 2, again == 0)
      },
      test("during a backoff, when it does not recover inside the drain timeout: the held rows are counted as not written") {
        for
          store <- Store.make(down = true)
          failedBefore <- counter("audit_events_failed_total", edge)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10)))
          _ <- events.record(draft()) *> events.record(draft())
          _ <- store.entered.take
          _ <- settle
          closing <- closedAsTheAppDoes(scope).fork
          _ <- settle *> TestClock.adjust(AuditEvents.DrainTimeout.plus(1.second)) *> settle
          _ <- closing.join
          stored <- store.stored.get
          failedAfter <- counter("audit_events_failed_total", edge)
        yield assertTrue(stored.isEmpty, failedAfter - failedBefore == 2.0)
      },
    ),
    suite("a request waiting for room when the stop begins")(
      test("is refused at once, without waiting for the writer to give up its statement or for the block timeout") {
        for
          // The writer is held in a statement the transactor will not interrupt, so the stop cannot free room for a second or more.
          store <- Store.make(holdFirst = true, uninterruptible = true)
          unavailableBefore <- counter("audit_events_unavailable_total", central)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = central, sizing = sizing.copy(queueCapacity = 2)))
          _ <- events.record(draft())
          _ <- store.entered.take
          _ <- events.record(draft()) *> events.record(draft())
          waiting <- events.record(draft()).exit.fork
          _ <- settle
          closing <- closedAsTheAppDoes(scope).fork
          // The clock is not moved: nothing but the stop itself can release the request.
          result <- eventually(waiting.poll)(_.isDefined)
          unavailableAfter <- counter("audit_events_unavailable_total", central)
          _ <- settle *> TestClock.adjust(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).plus(2.seconds)) *> settle
          _ <- closing.join
          _ <- store.gate.succeed(())
        yield assertTrue(result == Some(Exit.succeed(Exit.fail(AuditUnavailable))), unavailableAfter - unavailableBefore == 1.0)
      },
      test("a best-effort record waiting for room is released at once too, and the event is reported as offered after the stop began") {
        for
          store <- Store.make(holdFirst = true, uninterruptible = true)
          droppedBefore <- counter("audit_events_dropped_total", edge)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge, sizing = sizing.copy(queueCapacity = 2)))
          _ <- events.record(draft())
          _ <- store.entered.take
          _ <- events.record(draft()) *> events.record(draft())
          waiting <- events.recordBestEffort(draft()).fork
          _ <- settle
          closing <- closedAsTheAppDoes(scope).fork
          result <- eventually(waiting.poll)(_.isDefined)
          droppedAfter <- counter("audit_events_dropped_total", edge)
          _ <- settle *> TestClock.adjust(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).plus(2.seconds)) *> settle
          _ <- closing.join
          _ <- store.gate.succeed(())
        yield assertTrue(result.exists(_.isSuccess), droppedAfter - droppedBefore == 1.0)
      },
    ),
    suite("the stop and the app's shutdown")(
      test("the drain timeout and five seconds of margin fit inside the app's graceful shutdown timeout") {
        assertTrue(AuditEvents.DrainTimeout.plus(5.seconds).compareTo(VersolaApp.GracefulShutdownTimeout) <= 0)
      },
      test("the service refuses what is offered after it began to stop: a failure under block, a counted drop otherwise") {
        for
          store <- Store.make()
          unavailableBefore <- counter("audit_events_unavailable_total", central)
          droppedBefore <- counter("audit_events_dropped_total", edge)
          scopeBlock <- Scope.make
          blocking <- scopeBlock.extend(buildOn(store, block, source = central))
          scopeDrop <- Scope.make
          dropping <- scopeDrop.extend(buildOn(store, drop, source = edge))
          _ <- scopeBlock.close(Exit.unit) *> scopeDrop.close(Exit.unit)
          refused <- blocking.record(draft()).exit
          bestEffort <- blocking.recordBestEffort(draft()).exit
          droppedOne <- dropping.record(draft()).exit
          unavailableAfter <- counter("audit_events_unavailable_total", central)
          droppedAfter <- counter("audit_events_dropped_total", edge)
          stored <- store.stored.get
        yield assertTrue(
          refused == Exit.fail(AuditUnavailable),
          bestEffort.isSuccess,
          droppedOne.isSuccess,
          unavailableAfter - unavailableBefore == 1.0,
          droppedAfter - droppedBefore == 1.0,
          stored.isEmpty,
        )
      },
    ),
    suite("what the review of the writer asked to be shown")(
      test("a stop is not held by a statement the transactor will not interrupt: it returns inside the drain timeout, the held rows written again") {
        for
          store <- Store.make(holdFirst = true, uninterruptible = true)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge))
          _ <- events.record(draft())
          _ <- store.entered.take
          _ <- events.record(draft())
          closing <- closedAsTheAppDoes(scope).fork
          _ <- settle *> TestClock.adjust(AuditEvents.DrainTimeout.plus(2.seconds)) *> settle
          done <- closing.poll
          stored <- store.stored.get
          _ <- store.gate.succeed(())
        yield assertTrue(done.isDefined, stored.size == 2)
      },
      test("a stop with the writer held in a long statement and the drain's write held too returns by the sum of its two bounds, to the tick") {
        for
          // A statement of ten seconds, held uninterruptibly, is what a slow insert of a full batch is.
          store <- Store.make(holdAll = true, uninterruptible = true)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge))
          _ <- events.record(draft()) *> events.record(draft())
          _ <- store.entered.take
          closing <- closedAsTheAppDoes(scope).fork
          _ <- settle
          // Not a tick short of the bound: nothing has returned yet.
          _ <- TestClock.adjust(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).minus(1.millis))
          early <- eventually(closing.poll)(_ => true)
          _ <- TestClock.adjust(1.millis)
          done <- eventually(closing.poll)(_.isDefined)
          _ <- store.gate.succeed(())
        yield assertTrue(early.isEmpty, done.isDefined)
      },
      // Real time: the test clock cannot be advanced while a fiber sits in a call that holds a real thread.
      test("a stop against statements that hold a real thread and are never interrupted returns by the sum of its two bounds, and counts the rows") {
        val open = java.util.concurrent.atomic.AtomicBoolean(false)
        val source = AuditSource("svc-spin")
        for
          entered <- Queue.unbounded[Unit]
          scope <- Scope.make
          events <- scope.extend(buildOn(SpinningRepository(open, entered), block, source = source, sizing = sizing.copy(queueCapacity = 10)))
          _ <- events.record(draft()) *> events.record(draft())
          _ <- entered.take
          started <- Clock.nanoTime
          closing <- closedAsTheAppDoes(scope).fork
          result <- closing.join.timeout(AuditEvents.WriterStopWait.plus(AuditEvents.DrainTimeout).plus(3.seconds))
          elapsed <- Clock.nanoTime.map(now => (now - started) / 1000000)
          failed <- counter("audit_events_failed_total", source)
          _ <- ZIO.succeed(open.set(true))
        yield assertTrue(result.isDefined, elapsed >= 5000L, failed == 2.0)
      } @@ TestAspect.withLiveClock,
      test("a stop whose own write is not interrupted either returns inside the drain timeout, says so, and counts the rows as not written") {
        for
          store <- Store.make(holdAll = true, uninterruptible = true)
          failedBefore <- counter("audit_events_failed_total", edge)
          scope <- Scope.make
          events <- scope.extend(buildOn(store, block, source = edge))
          _ <- events.record(draft()) *> events.record(draft())
          _ <- store.entered.take
          closing <- closedAsTheAppDoes(scope).fork
          _ <- settle *> TestClock.adjust(AuditEvents.DrainTimeout.plus(2.seconds)) *> settle
          done <- closing.poll
          failedAfter <- counter("audit_events_failed_total", edge)
          drained <- drainLines
          _ <- store.gate.succeed(())
        yield assertTrue(done.isDefined, failedAfter - failedBefore == 2.0, drained.size == 1, drained.head.contains("not written"))
      }.provideLayer(ZTestLogger.default),
      test("a row that fails with a SQLSTATE that is not about the row, while the rest of its pass is stored, is kept and stored once it can be") {
        val broken = java.util.concurrent.atomic.AtomicBoolean(true)
        val readOnly = (event: AuditEvent) =>
          Option.when(broken.get && event.reason.contains("suspect"))(SQLException("cannot execute INSERT in a read-only transaction", "25006"))
        ZIO.scoped:
          for
            f <- fixture(gated = false, refuse = readOnly)
            events <- build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10))
            rejectedBefore <- counter("audit_events_rejected_total", edge)
            _ <- events.record(draft()) *> events.record(draft().copy(reason = Some("suspect"))) *> events.record(draft())
            _ <- until(f, 1)
            // The pass over the batch has to be over before the database comes back, whatever it did with the row.
            _ <- ZIO.foreachDiscard(1 to 3)(_ => settle *> TestClock.adjust(2.seconds) *> settle)
            rejectedDuring <- counter("audit_events_rejected_total", edge)
            _ <- ZIO.succeed(broken.set(false))
            written <- (settle *> TestClock.adjust(1.minute) *> f.written.get).repeatUntil(_.flatten.exists(_.reason.contains("suspect")))
          yield assertTrue(rejectedDuring == rejectedBefore, written.flatten.count(_.reason.contains("suspect")) == 1)
      },
      test("a row that keeps failing that way while the rest of its pass is stored is refused after five passes, and counted") {
        val readOnly = (event: AuditEvent) =>
          Option.when(event.reason.contains("suspect"))(SQLException("cannot execute INSERT in a read-only transaction", "25006"))
        for
          scope <- Scope.make
          f <- fixture(gated = false, refuse = readOnly)
          events <- scope.extend(build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10)))
          rejectedBefore <- counter("audit_events_rejected_total", edge)
          _ <- events.record(draft().copy(reason = Some("suspect")))
          _ <- ZIO.foreachDiscard(1 to 8)(_ => events.record(draft()) *> settle *> TestClock.adjust(1.minute) *> settle)
          rejectedAfter <- eventually(counter("audit_events_rejected_total", edge))(_ - rejectedBefore >= 1.0)
          written <- f.written.get.map(_.flatten)
          _ <- stopped(scope)
        yield assertTrue(rejectedAfter - rejectedBefore == 1.0, !written.exists(_.reason.contains("suspect")), written.size >= 8)
      },
      test("a batch with one refused row among sixty-four takes a handful of statements to settle, not one per row") {
        val poison = (event: AuditEvent) => Option.when(event.reason.contains("poison"))(SQLException("invalid input syntax for type inet", "22P02"))
        ZIO.scoped:
          for
            f <- fixture(gated = false, refuse = poison)
            events <- build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 100, batchSize = 100))
            _ <- ZIO.foreachDiscard(1 to 64)(i => events.record(draft().copy(reason = Some(if i == 17 then "poison" else "fine"))))
            written <- until(f, 6)
            calls = f.repository.insertBatch.calls.size
          yield assertTrue(written.flatten.size == 63, calls <= 25)
      },
      test("a pass stops at the first statement that fails for the database's reason instead of trying every row") {
        for
          scope <- Scope.make
          f <- fixture(gated = false, failures = 1000)
          events <- scope.extend(build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 100, batchSize = 100)))
          _ <- ZIO.foreachDiscard(1 to 20)(_ => events.record(draft()))
          _ <- settle *> TestClock.adjust(2.seconds) *> settle
          calls = f.repository.insertBatch.calls.size
          _ <- stopped(scope)
        yield assertTrue(calls <= 5)
      },
      test("the warning for a failing write names the exception class and SQLSTATE, and carries nothing the database said") {
        val secret = "secret-value-from-a-request"
        val failing = (_: AuditEvent) => Some(SQLException(s"invalid input syntax for type inet: \"$secret\"", "08006"))
        for
          scope <- Scope.make
          f <- fixture(gated = false, refuse = failing)
          events <- scope.extend(build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10)))
          _ <- events.record(draft())
          _ <- f.started.take
          _ <- settle
          entries <- (ZIO.yieldNow *> ZTestLogger.logOutput).repeatUntil(_.exists(_.message().contains("could not write")))
          warned = entries.filter(_.message().contains("could not write"))
          everything = entries.map(entry => entry.message() + " " + entry.cause.prettyPrint).mkString("\n")
          _ <- stopped(scope)
        yield assertTrue(warned.size == 1, warned.head.message().contains("08006"), !everything.contains(secret))
          .label(entries.map(_.message()).mkString(" | "))
      }.provideLayer(ZTestLogger.default),
    ),
    suite("overflow = drop")(
      test("an event that finds the queue full is dropped and counted, and the request is not held up or failed") {
        ZIO.scoped:
          for
            f <- fixture(gated = true)
            events <- build(f, drop, source = edge)
            droppedBefore <- counter("audit_events_dropped_total", edge)
            _ <- events.record(draft())
            _ <- f.started.take
            results <- ZIO.foreach(1 to 4)(_ => events.record(draft()).exit)
            droppedAfter <- counter("audit_events_dropped_total", edge)
          yield assertTrue(results.forall(_.isSuccess), droppedAfter - droppedBefore == 2.0)
      },
    ),
    suite("scope")(
      test("what is queued when the scope closes is written on the way out together with the batch in hand, and none of it is counted failed") {
        for
          f <- fixture(gated = true)
          failedBefore <- counter("audit_events_failed_total", edge)
          _ <- ZIO.scoped:
            for
              events <- build(f, block, source = edge)
              _ <- events.record(draft())
              _ <- f.started.take
              _ <- events.record(draft()) *> events.record(draft())
            yield ()
          written <- f.written.get
          failedAfter <- counter("audit_events_failed_total", edge)
        yield assertTrue(written.flatten.size == 3, failedAfter == failedBefore)
      },
    ),
    suite("partition maintenance")(
      test("makes the partitions ahead and prunes the default partition at start, then every interval") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            _ <- TestClock.setTime(Instant.parse("2026-11-03T10:00:00Z"))
            // The writer's and the report's clocks are slowed down: the test moves the clock by an hour, a tick of theirs at a time.
            _ <- build(f, block, sizing = sizing.copy(flushInterval = 30.minutes, reportWindow = 30.minutes))
            _ <- settle
            atStart = f.repository.maintainPartitions.calls
            pruned = f.repository.pruneDefault.calls
            _ <- TestClock.adjust(1.hour)
            _ <- eventually(ZIO.succeed((f.repository.maintainPartitions.calls.size, f.repository.pruneDefault.calls.size)))(_ == (2, 2))
          yield assertTrue(
            // today, the days ahead plus today, and the first day that is kept: retention is 7 days.
            atStart == List((LocalDate.parse("2026-11-03"), 4, LocalDate.parse("2026-10-27"))),
            pruned == List(Instant.parse("2026-10-27T10:00:00Z")),
            f.repository.maintainPartitions.calls.size == 2,
            f.repository.pruneDefault.calls.size == 2,
          )
      },
      test("a day left in the default partition is a gauge and is not retried as a failure; the days ahead are a gauge too") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            _ <- f.repository.maintainPartitions.succeedsWith(
              noReport.copy(daysLeftInDefault = List(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-05"))),
            )
            _ <- f.repository.partitionDaysAhead.succeedsWith(1)
            _ <- build(f, block, source = edge)
            _ <- settle
            inDefault <- gauge("audit_events_in_default", edge)
            ahead <- gauge("audit_partitions_ahead", edge)
          yield assertTrue(inDefault == 2.0, ahead == 1.0, f.repository.maintainPartitions.calls.size == 1)
      },
      test("a service that does not own the table sets the gauge and attempts no partition DDL") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            _ <- f.repository.ownership.succeedsWith(TableOwnership(owner = "migrator", role = "versola-auth", mayAlter = false))
            _ <- build(f, block, source = edge)
            _ <- settle
            mismatch <- gauge("audit_partition_owner_mismatch", edge)
          yield assertTrue(mismatch == 1.0, f.repository.maintainPartitions.calls.isEmpty)
      },
      test("a role that owns the table clears the gauge") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            _ <- build(f, block, source = edge)
            _ <- settle
            mismatch <- gauge("audit_partition_owner_mismatch", edge)
          yield assertTrue(mismatch == 0.0, f.repository.maintainPartitions.calls.size == 1)
      },
      test("a run that fails is tried again after a minute, not after the interval, and the days ahead are read after it") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            attempts <- Ref.make(0)
            _ <- f.repository.maintainPartitions.returnsZIO: _ =>
              attempts.updateAndGet(_ + 1).flatMap(n => if n == 1 then ZIO.fail(RuntimeException("lock timeout")) else ZIO.succeed(noReport))
            _ <- build(f, block)
            _ <- settle
            first = f.repository.maintainPartitions.calls.size
            _ <- TestClock.adjust(1.minute)
            _ <- eventually(ZIO.succeed((f.repository.maintainPartitions.calls.size, f.repository.partitionDaysAhead.calls.size)))((runs, ahead) =>
              runs >= 2 && ahead >= 1,
            )
          yield assertTrue(first == 1, f.repository.maintainPartitions.calls.size == 2, f.repository.partitionDaysAhead.calls.size >= 1)
      },
      test("the service keeps fourteen days of partitions ahead and a maintenance run an hour") {
        assertTrue(AuditEvents.Sizing.standard.partitionsAhead == 14, AuditEvents.Sizing.standard.maintenanceInterval == 1.hour)
      },
    ),
    suite("noop")(
      test("records nothing and never fails") {
        for
          a <- AuditEvents.noop.record(draft()).exit
          _ <- AuditEvents.noop.recordBestEffort(draft())
        yield assertTrue(a.isSuccess)
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
