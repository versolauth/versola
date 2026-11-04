package versola.util.audit

import io.opentelemetry.api
import org.scalamock.stubs.Stub
import versola.util.http.Observability
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
  ): ZIO[Scope, Nothing, AuditEvents] =
    AuditEvents.liveWith(sizing)(source, nodeId, catalog).build.map(_.get[AuditEvents]).provideSome[Scope](
      ZLayer.succeed[AuditEventRepository](f.repository),
      ZLayer.succeed(settings),
      tracingLayer,
      SecureRandom.live,
    )

  private def counter(name: String, source: AuditSource): UIO[Double] =
    Metric.counter(name).tagged(Set(MetricLabel("source", source.value))).value.map(_.count)

  private def gauge(name: String, source: AuditSource): UIO[Double] =
    Metric.gauge(name).tagged(Set(MetricLabel("source", source.value))).value.map(_.value)

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
            _ <- settle
            written <- f.written.get
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
        ZIO.scoped:
          for
            f <- fixture(gated = false, refuse = down)
            events <- build(f, block, source = edge, sizing = sizing.copy(queueCapacity = 10, batchSize = 10))
            rejectedBefore <- counter("audit_events_rejected_total", edge)
            _ <- events.record(draft()) *> events.record(draft())
            _ <- settle *> TestClock.adjust(1.minute).repeatN(3)
            written <- f.written.get
            rejectedAfter <- counter("audit_events_rejected_total", edge)
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
    suite("an event that is not queued")(
      test("build gives the event a draft would be stored as, and writes and queues nothing") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            _ <- TestClock.setTime(Instant.parse("2026-11-03T10:00:00Z"))
            events <- build(f, block)
            _ <- Observability.setUserId(userId.toString) *> Observability.setIp("203.0.113.7")
            _ <- AuditContext.setTenant("ten\u0000ant") *> AuditContext.setTarget("client", "client-a")
            event <- events.build(draft(Changed, AuditOutcome.success).copy(reason = Some("re\u0000ason")))
            _ <- settle *> TestClock.adjust(10.seconds)
          yield assertTrue(
            event.at == Instant.parse("2026-11-03T10:00:00Z"),
            event.source == svcA,
            event.nodeId == nodeId,
            event.kind == Changed,
            event.severity == AuditSeverity.high,
            event.actorType == ActorType.user,
            event.userId == Some(userId),
            event.ip == Some("203.0.113.7"),
            event.targetId == Some("client-a"),
            // Made storable, as for a queued event.
            event.tenantId == Some("tenant"),
            event.reason == Some("reason"),
            f.repository.insertBatch.calls.isEmpty,
          )
      },
      test("build gives every event an id of its own") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block)
            built <- ZIO.foreach(1 to 5)(_ => events.build(draft()))
          yield assertTrue(built.map(_.id).distinct.size == 5)
      },
      test("recordNow writes the event in one row before it returns, and the writer is not involved") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block, source = central)
            _ <- events.recordNow(draft(Issued, AuditOutcome.success))
            written <- f.written.get
            _ <- settle *> TestClock.adjust(10.seconds)
            later <- f.written.get
          yield assertTrue(
            written.map(_.map(_.kind)) == List(List(Issued)),
            // Nothing else is written later: it was never queued.
            later == written,
            f.repository.insertBatch.calls.size == 1,
          )
      },
      test("recordNow counts the event as recorded after the insert") {
        val labels = Set(MetricLabel("source", "svc-b"), MetricLabel("kind", "thing.issued"), MetricLabel("outcome", "success"))
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block, source = central)
            before <- Metric.counter("audit_events_total").tagged(labels).value.map(_.count)
            _ <- events.recordNow(draft(Issued, AuditOutcome.success))
            after <- Metric.counter("audit_events_total").tagged(labels).value.map(_.count)
          yield assertTrue(after - before == 1.0)
      },
      test("recordNow fails the caller with AuditUnavailable when the insert fails, and counts the refusal and not the event") {
        val labels = Set(MetricLabel("source", "svc-c"), MetricLabel("kind", "thing.issued"), MetricLabel("outcome", "success"))
        ZIO.scoped:
          for
            f <- fixture(gated = false, failures = 100)
            events <- build(f, block, source = edge)
            before <- Metric.counter("audit_events_total").tagged(labels).value.map(_.count)
            unavailableBefore <- counter("audit_events_unavailable_total", edge)
            result <- events.recordNow(draft(Issued, AuditOutcome.success)).exit
            after <- Metric.counter("audit_events_total").tagged(labels).value.map(_.count)
            unavailableAfter <- counter("audit_events_unavailable_total", edge)
            written <- f.written.get
          yield assertTrue(
            result == Exit.fail(AuditUnavailable),
            after == before,
            unavailableAfter - unavailableBefore == 1.0,
            written.isEmpty,
            // One attempt, no retry: the action does not go ahead.
            f.repository.insertBatch.calls.size == 1,
          )
      },
      test("recordNow does not depend on the queue: it writes under overflow = drop too, and a refused row is not retried by the writer") {
        ZIO.scoped:
          for
            f <- fixture(gated = false, failures = 1)
            events <- build(f, drop, source = edge)
            first <- events.recordNow(draft()).exit
            _ <- settle *> TestClock.adjust(1.minute)
            second <- events.recordNow(draft()).exit
            written <- f.written.get
          yield assertTrue(
            first == Exit.fail(AuditUnavailable),
            second.isSuccess,
            written.map(_.size) == List(1),
            f.repository.insertBatch.calls.size == 2,
          )
      },
      test("committed counts an event a repository inserted in its own transaction, with the kind label of the catalog") {
        val labels = (kind: String) => Set(MetricLabel("source", "svc-c"), MetricLabel("kind", kind), MetricLabel("outcome", "success"))
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            events <- build(f, block, source = edge)
            listed <- events.build(draft(Issued, AuditOutcome.success))
            unlisted <- events.build(draft(AuditKind("thing.unlisted"), AuditOutcome.success))
            issuedBefore <- Metric.counter("audit_events_total").tagged(labels("thing.issued")).value.map(_.count)
            otherBefore <- Metric.counter("audit_events_total").tagged(labels("other")).value.map(_.count)
            _ <- events.committed(listed) *> events.committed(unlisted)
            issuedAfter <- Metric.counter("audit_events_total").tagged(labels("thing.issued")).value.map(_.count)
            otherAfter <- Metric.counter("audit_events_total").tagged(labels("other")).value.map(_.count)
          yield assertTrue(issuedAfter - issuedBefore == 1.0, otherAfter - otherBefore == 1.0, f.repository.insertBatch.calls.isEmpty)
      },
      test("the noop service builds an event to hand to a repository and records nothing") {
        for
          event <- AuditEvents.noop.build(draft(Refused, AuditOutcome.failure).copy(reason = Some("invalid_grant")))
          now <- AuditEvents.noop.recordNow(draft()).exit
          _ <- AuditEvents.noop.committed(event)
        yield assertTrue(
          event.kind == Refused,
          event.reason == Some("invalid_grant"),
          event.severity == AuditSeverity.notice,
          event.source == AuditSource("noop"),
          now.isSuccess,
        )
      },
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
      test("what is queued when the scope closes is written once more on the way out, and the batch in hand is counted failed") {
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
        yield assertTrue(written.map(_.size) == List(2), failedAfter - failedBefore == 1.0)
      },
    ),
    suite("partition maintenance")(
      test("makes the partitions ahead and prunes the default partition at start, then every interval") {
        ZIO.scoped:
          for
            f <- fixture(gated = false)
            _ <- TestClock.setTime(Instant.parse("2026-11-03T10:00:00Z"))
            _ <- build(f, block)
            _ <- settle
            atStart = f.repository.maintainPartitions.calls
            pruned = f.repository.pruneDefault.calls
            _ <- TestClock.adjust(1.hour)
            _ <- settle
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
            _ <- settle
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
  ) @@ TestAspect.sequential @@ TestAspect.timeout(15.seconds)
