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
    suite("what the sanitizer caps and reports")(
      test("a text is cut to its bound, a user agent to its own, and the field is reported") {
        val long = "x" * (AuditSanitizer.MaxText + 40)
        val (cut, fields) = AuditSanitizer.run(event.copy(reason = Some(long), targetId = Some(long), userAgent = Some("a" * 700)))
        assertTrue(
          cut.reason.map(_.length) == Some(AuditSanitizer.MaxText),
          cut.targetId.map(_.length) == Some(AuditSanitizer.MaxText),
          cut.userAgent.map(_.length) == Some(AuditSanitizer.MaxUserAgent),
          fields == List(AuditSanitizer.Field.Text, AuditSanitizer.Field.UserAgent),
        )
      },
      test("details past the bound are replaced by a marker, and the field is reported") {
        val big = event.copy(details = Json.Obj("list" -> Json.Arr(Chunk.fromIterable(List.fill(2000)(Json.Str("0123456789"))))))
        val (cut, fields) = AuditSanitizer.run(big)
        assertTrue(cut.details == Json.Obj("truncated" -> Json.Bool(true)), fields == List(AuditSanitizer.Field.Details))
      },
      test("an address that is none is dropped and reported, and an event that needed nothing reports nothing") {
        val (dropped, fields) = AuditSanitizer.run(event.copy(ip = Some("not an address")))
        val (same, none) = AuditSanitizer.run(event.copy(ip = Some("203.0.113.7")))
        assertTrue(dropped.ip.isEmpty, fields == List(AuditSanitizer.Field.Ip), same.ip == Some("203.0.113.7"), none.isEmpty)
      },
      test("U+0000 is reported as a change of text, and of the details when it was in them") {
        val (_, text) = AuditSanitizer.run(event.copy(reason = Some("a\u0000b")))
        val (_, details) = AuditSanitizer.run(event.copy(details = Json.Obj("k" -> Json.Str("v\u0000"))))
        assertTrue(text == List(AuditSanitizer.Field.Text), details == List(AuditSanitizer.Field.Details))
      },
    ),
    suite("what a statement's error says about its rows")(
      test("a data exception, a constraint violation or a value too large for an index is the row's") {
        val states = List("22P02", "22021", "22P05", "23502", "23505", "54000")
        assertTrue(
          states.forall(state => AuditEvents.verdictOf(SQLException("bad", state)) == AuditEvents.Verdict.Data),
          AuditEvents.verdictOf(RuntimeException("wrapped", RuntimeException("deeper", SQLException("bad", "23505")))) == AuditEvents.Verdict.Data,
        )
      },
      test("a failure with no SQLSTATE or one about the server is the database's, and is never the row's") {
        val states = List("08006", "53300", "55000", "57P01", "58030", "40001", "XX000")
        assertTrue(
          states.forall(state => AuditEvents.verdictOf(SQLException("bad", state)) == AuditEvents.Verdict.Infrastructure),
          AuditEvents.verdictOf(RuntimeException("timeout")) == AuditEvents.Verdict.Infrastructure,
        )
      },
      test(
        "any other SQLSTATE is neither: a read-only transaction after a failover, a credential rotation, a missing database or a missing privilege",
      ) {
        val states = List("25006", "28P01", "3D000", "42501", "42P01")
        assertTrue(states.forall(state => AuditEvents.verdictOf(SQLException("bad", state)) == AuditEvents.Verdict.Other))
      },
    ),
  )
