package versola.util.audit

import versola.util.UnitSpecBase
import zio.Chunk
import zio.json.ast.Json
import zio.test.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

object AuditSanitizerSpec extends UnitSpecBase:

  private val event = AuditEvent(
    id = UUID.fromString("5f1c8f0e-6a3e-4a52-9d3e-2b1c0f6f3a01"),
    at = Instant.parse("2026-11-04T10:00:00Z"),
    tenantId = Some("tenant"),
    source = AuditSource("svc"),
    nodeId = "node",
    kind = AuditKind("thing.done"),
    outcome = AuditOutcome.success,
    reason = None,
    severity = AuditSeverity.info,
    actorType = ActorType.user,
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

  val spec = suite("AuditSanitizer")(
    suite("U+0000")(
      test("is stripped from every text of the event") {
        val dirty = event.copy(
          tenantId = Some("a\u0000"),
          nodeId = "\u0000n",
          reason = Some("r\u0000r"),
          actorId = Some("\u0000"),
          clientId = Some("c\u0000"),
          sessionId = Some("s\u0000"),
          userAgent = Some("u\u0000a"),
          traceId = Some("t\u0000"),
          targetType = Some("x\u0000"),
          targetId = Some("y\u0000"),
        )
        val clean = AuditSanitizer(dirty)
        assertTrue(
          clean.tenantId == Some("a"),
          clean.nodeId == "n",
          clean.reason == Some("rr"),
          clean.actorId == Some(""),
          clean.clientId == Some("c"),
          clean.sessionId == Some("s"),
          clean.userAgent == Some("ua"),
          clean.traceId == Some("t"),
          clean.targetType == Some("x"),
          clean.targetId == Some("y"),
        )
      },
      test("is stripped from the strings and the keys of the details, at any depth") {
        val dirty = event.copy(details =
          Json.Obj(
            "k\u0000ey" -> Json.Str("v\u0000"),
            "list" -> Json.Arr(Json.Str("a\u0000"), Json.Num(1), Json.Obj("in\u0000" -> Json.Str("\u0000x"))),
            "n" -> Json.Null,
            "b" -> Json.Bool(true),
          ),
        )
        assertTrue(
          AuditSanitizer(dirty).details == Json.Obj(
            "key" -> Json.Str("v"),
            "list" -> Json.Arr(Json.Str("a"), Json.Num(1), Json.Obj("in" -> Json.Str("x"))),
            "n" -> Json.Null,
            "b" -> Json.Bool(true),
          ),
        )
      },
      test("an event with none is the same event") {
        assertTrue(AuditSanitizer(event) == event)
      },
    ),
    suite("ip")(
      test("an IPv4 or IPv6 literal is kept as it was written") {
        assertTrue(
          List("203.0.113.7", "2001:db8::1", "::1", "::ffff:192.0.2.1").forall(literal =>
            AuditSanitizer(event.copy(ip = Some(literal))).ip == Some(literal),
          ),
        )
      },
      test("text that is not an address is dropped, a host name and a list of addresses included") {
        assertTrue(
          List("not an address", "example.com", "1.2.3", "999.1.1.1", "203.0.113.7, 198.51.100.2", "", "203.0.113.7/24", "fe80::1%eth0")
            .forall(text => AuditSanitizer(event.copy(ip = Some(text))).ip.isEmpty),
        )
      },
    ),
    suite("user agent")(
      test("one of the bound is kept, a longer one is cut to it") {
        val bound = "a" * AuditSanitizer.MaxUserAgent
        assertTrue(
          AuditSanitizer(event.copy(userAgent = Some(bound))).userAgent == Some(bound),
          AuditSanitizer(event.copy(userAgent = Some(bound + "b"))).userAgent == Some(bound),
        )
      },
      test("a cut never leaves the first half of a surrogate pair") {
        val cut = AuditSanitizer(event.copy(userAgent = Some("a" * (AuditSanitizer.MaxUserAgent - 1) + "😀"))).userAgent
        assertTrue(cut == Some("a" * (AuditSanitizer.MaxUserAgent - 1)))
      },
    ),
    suite("which refusals of the database drop a row")(
      test("a data exception or a constraint violation always does") {
        assertTrue(
          AuditEvents.refusedForItself(SQLException("bad", "22P02"), othersStored = false),
          AuditEvents.refusedForItself(SQLException("bad", "22021"), othersStored = false),
          AuditEvents.refusedForItself(SQLException("bad", "23502"), othersStored = false),
          AuditEvents.refusedForItself(RuntimeException("wrapped", SQLException("bad", "22P02")), othersStored = false),
          AuditEvents.refusedForItself(RuntimeException("wrapped", RuntimeException("deeper", SQLException("bad", "23505"))), othersStored = false),
        )
      },
      test("a failure that carries no SQLSTATE or says the database is the problem never does, whatever else was stored") {
        assertTrue(
          !AuditEvents.refusedForItself(RuntimeException("timeout"), othersStored = true),
          !AuditEvents.refusedForItself(SQLException("gone", "08006"), othersStored = true),
          !AuditEvents.refusedForItself(SQLException("no resources", "53300"), othersStored = true),
          !AuditEvents.refusedForItself(SQLException("shutdown", "57P01"), othersStored = true),
          !AuditEvents.refusedForItself(SQLException("rolled back", "40001"), othersStored = true),
          !AuditEvents.refusedForItself(SQLException("bad", "42P01"), othersStored = false),
        )
      },
      test("another SQLSTATE drops a row only when other rows of the same pass were stored, which shows it is the row") {
        assertTrue(
          AuditEvents.refusedForItself(SQLException("too long", "54000"), othersStored = true),
          !AuditEvents.refusedForItself(SQLException("too long", "54000"), othersStored = false),
        )
      },
    ),
  )
