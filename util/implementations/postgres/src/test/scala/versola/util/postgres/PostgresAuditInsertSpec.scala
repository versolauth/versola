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

  private case class Row(
      id: UUID,
      at: Instant,
      tenantId: Option[String],
      source: String,
      nodeId: String,
      kind: String,
      outcome: String,
      reason: Option[String],
      severity: String,
      actorType: String,
      actorId: Option[String],
      userId: Option[UUID],
      clientId: Option[String],
      sessionId: Option[String],
      ip: Option[String],
      userAgent: Option[String],
      traceId: Option[String],
      targetType: Option[String],
      targetId: Option[String],
      details: String,
  ) derives DbCodec

  /** A transaction that sees the copy of the table, as the service's connection sees its own. */
  private def inTransaction[A](xa: TransactorZIO)(f: DbTx ?=> A): Task[A] =
    xa.transact:
      sql"SET LOCAL search_path = audit_insert_spec, public".update.run()
      f

  private def rows(xa: TransactorZIO): Task[List[Row]] =
    inTransaction(xa):
      sql"""
        SELECT id, at, tenant_id, source, node_id, kind, outcome, reason, severity, actor_type, actor_id, user_id, client_id,
               session_id, host(ip), user_agent, trace_id, target_type, target_id, details::text
        FROM audit_event ORDER BY kind, id
      """.query[Row].run().toList

  private def count(xa: TransactorZIO, table: String): Task[Long] =
    inTransaction(xa)(sql"SELECT COUNT(*) FROM ${SqlLiteral(table)}".query[Long].run().head)

  private val reset: URIO[TransactorZIO, Unit] =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect:
        sql"DROP SCHEMA IF EXISTS audit_insert_spec CASCADE".update.run()
        sql"CREATE SCHEMA audit_insert_spec".update.run()
        sql"""
          CREATE TABLE audit_insert_spec.audit_event (
            id UUID NOT NULL, at TIMESTAMPTZ NOT NULL, tenant_id TEXT, source TEXT NOT NULL, node_id TEXT NOT NULL,
            kind TEXT NOT NULL, outcome TEXT NOT NULL, reason TEXT, severity TEXT NOT NULL,
            actor_type TEXT NOT NULL, actor_id TEXT, user_id UUID, client_id TEXT, session_id TEXT,
            ip INET, user_agent TEXT, trace_id TEXT, target_type TEXT, target_id TEXT,
            details JSONB NOT NULL,
            PRIMARY KEY (id, at)
          ) PARTITION BY RANGE (at)
        """.update.run()
        sql"CREATE TABLE audit_insert_spec.audit_event_default PARTITION OF audit_insert_spec.audit_event DEFAULT".update.run()
        sql"CREATE TABLE audit_insert_spec.change_probe (id INT PRIMARY KEY)".update.run()
        // What stands for an event the table cannot take: a constraint of the real table that this copy does not have.
        sql"""
          CREATE FUNCTION audit_insert_spec.refuse_poison() RETURNS trigger AS $$$$
          BEGIN
            IF NEW.kind = 'thing.poison' THEN RAISE EXCEPTION 'refused'; END IF;
            RETURN NEW;
          END $$$$ LANGUAGE plpgsql
        """.update.run()
        sql"""
          CREATE TRIGGER refuse_poison BEFORE INSERT ON audit_insert_spec.audit_event
          FOR EACH ROW EXECUTE FUNCTION audit_insert_spec.refuse_poison()
        """.update.run()
        ()
      .orDie

  private val dirty = event().copy(
    tenantId = Some("ten\u0000ant"),
    reason = Some("re\u0000ason"),
    ip = Some("not an address"),
    userAgent = Some("a" * 600),
    details = Json.Obj("k\u0000ey" -> Json.Str("v\u0000")),
  )

  private def assertSanitized(row: Row) =
    assertTrue(
      row.tenantId == Some("tenant"),
      row.reason == Some("reason"),
      row.ip.isEmpty,
      row.userAgent.map(_.length) == Some(AuditSanitizer.MaxUserAgent),
      row.details == """{"key": "v"}""",
    )

  val spec = suite("AuditInsert")(
    suite("insert")(
      test("stores every column of the event in the transaction it is given") {
        val e = event()
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa)(AuditInsert.insert(e))
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
          _ <- inTransaction(xa)(AuditInsert.insert(e))
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
      test("makes the text storable first: U+0000 stripped, an address that is none dropped, a user agent cut") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa)(AuditInsert.insert(dirty))
          stored <- rows(xa).map(_.head)
        yield assertSanitized(stored)
      },
      test("a transaction that rolls back leaves no row") {
        for
          xa <- ZIO.service[TransactorZIO]
          result <- inTransaction(xa):
            AuditInsert.insert(event())
            throw RuntimeException("the change failed")
          .exit
          left <- count(xa, "audit_event")
        yield assertTrue(result.isFailure, left == 0L)
      },
      test("the change and its row commit together") {
        val e = event()
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa):
            sql"INSERT INTO change_probe (id) VALUES (1)".update.run()
            AuditInsert.insert(e)
          changed <- count(xa, "change_probe")
          audited <- count(xa, "audit_event")
        yield assertTrue(changed == 1L, audited == 1L)
      },
      test("an insert the table refuses fails the transaction and takes the change with it") {
        for
          xa <- ZIO.service[TransactorZIO]
          result <- inTransaction(xa):
            sql"INSERT INTO change_probe (id) VALUES (1)".update.run()
            AuditInsert.insert(event().copy(kind = AuditKind("thing.poison")))
          .exit
          changed <- count(xa, "change_probe")
          audited <- count(xa, "audit_event")
        yield assertTrue(result.isFailure, changed == 0L, audited == 0L)
      },
      test("the same event inserted again in the same transaction is one row") {
        val e = event()
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa):
            AuditInsert.insert(e)
            AuditInsert.insert(e)
          audited <- count(xa, "audit_event")
        yield assertTrue(audited == 1L)
      },
      test("the same event inserted again in another transaction is one row, the first one's, and the second transaction succeeds") {
        val e = event()
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa)(AuditInsert.insert(e))
          second <- inTransaction(xa):
            sql"INSERT INTO change_probe (id) VALUES (1)".update.run()
            AuditInsert.insert(e.copy(reason = Some("later")))
          .exit
          stored <- rows(xa)
          changed <- count(xa, "change_probe")
        yield assertTrue(second.isSuccess, stored.size == 1, stored.head.reason == Some("invalid_grant"), changed == 1L)
      },
      test("an event with the same id at another time is another row") {
        val e = event()
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa):
            AuditInsert.insert(e)
            AuditInsert.insert(e.copy(at = at.plusSeconds(1)))
          audited <- count(xa, "audit_event")
        yield assertTrue(audited == 2L)
      },
    ),
    suite("insertBatch")(
      test("stores the events in one go") {
        val events = List(event(), event().copy(kind = AuditKind("thing.other")), event())
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa)(AuditInsert.insertBatch(events))
          stored <- rows(xa)
        yield assertTrue(stored.map(_.id).toSet == events.map(_.id).toSet, stored.size == 3)
      },
      test("makes the text storable first, as insert does") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa)(AuditInsert.insertBatch(List(dirty)))
          stored <- rows(xa).map(_.head)
        yield assertSanitized(stored)
      },
      test("an event already stored, or twice in the batch, is stored once and does not fail the batch") {
        val e = event()
        val other = event()
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- inTransaction(xa)(AuditInsert.insert(e))
          _ <- inTransaction(xa)(AuditInsert.insertBatch(List(e, other, other)))
          stored <- rows(xa)
        yield assertTrue(stored.map(_.id).toSet == Set(e.id, other.id), stored.size == 2)
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
    ),
  ) @@ TestAspect.sequential @@ TestAspect.before(reset) @@ TestAspect.timeout(60.seconds)
