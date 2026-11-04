package versola.util.postgres

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.audit.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The one INSERT of an audit event, on a copy of the table in a schema of its own (the service's migration makes the real one, and
  * this must not make a second in the schema the migrations run in). The DDL is the audit log's: partitioned by day, with the
  * default partition every event of a day without a partition goes to. */
object PostgresAuditInsertSpec extends PostgresSpec:

  private val Schema = "audit_insert_spec"

  private val at = Instant.parse("2026-11-04T10:00:00Z")
  private val userId = UUID.fromString("5f1c8f0e-6a3e-4a52-9d3e-2b1c0f6f3a01")

  private def event(id: UUID = UUID.randomUUID()) =
    AuditEvent(
      id = id,
      at = at,
      tenantId = Some("tenant-a"),
      source = AuditSource("svc"),
      nodeId = "node-1",
      kind = AuditKind("thing.done"),
      outcome = AuditOutcome.failure,
      reason = Some("invalid_grant"),
      severity = AuditSeverity.notice,
      actorType = ActorType("admin"),
      actorId = Some("admin-1"),
      userId = Some(userId),
      clientId = Some("client-a"),
      sessionId = Some("session-1"),
      ip = Some("2001:db8::1"),
      userAgent = Some("Firefox 130 / macOS"),
      traceId = Some("0af7651916cd43dd8448eb211c80319c"),
      targetType = Some("client"),
      targetId = Some("client-a"),
      details = Json.Obj("changed" -> Json.Arr(Json.Str("name")), "n" -> Json.Num(1)),
    )

  private val table = AuditTestTable("audit_insert_spec")

  private type Row = AuditTestTable.Row

  private def inTransaction[A](xa: TransactorZIO)(f: DbTx ?=> A): Task[A] = table.inTransaction(xa)(f)

  private def rows(xa: TransactorZIO): Task[List[Row]] = table.rows(xa)

  private def count(xa: TransactorZIO, name: String): Task[Long] = table.count(xa, name)

  private val reset = table.reset

  val spec = suite("AuditInsert.insertBatch")(
    test("stores every column of the event") {
      val e = event()
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- inTransaction(xa)(AuditInsert.insertBatch(List(e)))
        stored <- rows(xa)
      yield assertTrue(
        stored.map(_.id) == List(e.id),
        stored.head.at == at,
        stored.head.tenantId == Some("tenant-a"),
        stored.head.source == "svc",
        stored.head.nodeId == "node-1",
        stored.head.kind == "thing.done",
        stored.head.outcome == "failure",
        stored.head.reason == Some("invalid_grant"),
        stored.head.severity == "notice",
        stored.head.actorType == "admin",
        stored.head.actorId == Some("admin-1"),
        stored.head.userId == Some(userId),
        stored.head.clientId == Some("client-a"),
        stored.head.sessionId == Some("session-1"),
        stored.head.ip == Some("2001:db8::1"),
        stored.head.userAgent == Some("Firefox 130 / macOS"),
        stored.head.traceId == Some("0af7651916cd43dd8448eb211c80319c"),
        stored.head.targetType == Some("client"),
        stored.head.targetId == Some("client-a"),
        stored.head.details == """{"n": 1, "changed": ["name"]}""" || stored.head.details == """{"changed": ["name"], "n": 1}""",
      )
    },
    test("an event with none of the optional columns stores them empty and its details as an empty object") {
      val e = event().copy(
        tenantId = None,
        reason = None,
        actorId = None,
        userId = None,
        clientId = None,
        sessionId = None,
        ip = None,
        userAgent = None,
        traceId = None,
        targetType = None,
        targetId = None,
        details = Json.Obj(),
      )
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- inTransaction(xa)(AuditInsert.insertBatch(List(e)))
        stored <- rows(xa).map(_.head)
      yield assertTrue(
        stored.tenantId.isEmpty,
        stored.reason.isEmpty,
        stored.userId.isEmpty,
        stored.ip.isEmpty,
        stored.userAgent.isEmpty,
        stored.targetId.isEmpty,
        stored.details == "{}",
      )
    },
    test("stores several events in one go") {
      val events = List(event(), event().copy(kind = AuditKind("thing.other")), event())
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- inTransaction(xa)(AuditInsert.insertBatch(events))
        stored <- rows(xa)
      yield assertTrue(stored.map(_.id).toSet == events.map(_.id).toSet, stored.size == 3)
    },
    test("a batch retried after an unclear failure writes no duplicate: the same events again in another transaction are the same rows") {
      val events = List(event(), event())
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- inTransaction(xa)(AuditInsert.insertBatch(events))
        retried <- inTransaction(xa)(AuditInsert.insertBatch(events)).exit
        stored <- rows(xa)
      yield assertTrue(retried.isSuccess, stored.map(_.id).toSet == events.map(_.id).toSet, stored.size == 2)
    },
    test("a retry that carries more than the first batch stores only the new events, and the first insert of an event wins") {
      val first = event()
      val second = event()
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- inTransaction(xa)(AuditInsert.insertBatch(List(first)))
        _ <- inTransaction(xa)(AuditInsert.insertBatch(List(first.copy(reason = Some("later")), second)))
        stored <- rows(xa)
      yield assertTrue(stored.map(_.id).toSet == Set(first.id, second.id), stored.find(_.id == first.id).flatMap(_.reason) == Some("invalid_grant"))
    },
    test("an event twice in one batch is stored once, and an event with the same id at another time is another row") {
      val e = event()
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- inTransaction(xa)(AuditInsert.insertBatch(List(e, e, e.copy(at = at.plusSeconds(1)))))
        audited <- count(xa, "audit_event")
      yield assertTrue(audited == 2L)
    },
    test("an empty list writes nothing") {
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- inTransaction(xa)(AuditInsert.insertBatch(Nil))
        left <- count(xa, "audit_event")
      yield assertTrue(left == 0L)
    },
    test("a batch that rolls back leaves no row") {
      for
        xa <- ZIO.service[TransactorZIO]
        result <- inTransaction(xa):
          AuditInsert.insertBatch(List(event(), event()))
          throw RuntimeException("the change failed")
        .exit
        left <- count(xa, "audit_event")
      yield assertTrue(result.isFailure, left == 0L)
    },
    test("a batch with a row the table refuses fails as a whole and stores none of it") {
      for
        xa <- ZIO.service[TransactorZIO]
        result <- inTransaction(xa)(AuditInsert.insertBatch(List(event(), event().copy(kind = AuditKind("thing.poison")), event()))).exit
        left <- count(xa, "audit_event")
      yield assertTrue(result.isFailure, left == 0L)
    },
  ) @@ TestAspect.sequential @@ TestAspect.before(reset) @@ TestAspect.timeout(60.seconds)
