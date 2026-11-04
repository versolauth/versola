package versola.util.audit

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.{AuditInsert, AuditTestTable, PostgresSpec}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

/** What the sanitizer promises, against the database that has to take the result: a row that is refused as it came is stored once
  * it has been through [[AuditSanitizer]], and what the database refuses is refused with the SQLSTATE the writer classifies by. */
object PostgresAuditSanitizerSpec extends PostgresSpec:

  private val Table = "sanitizer_spec_audit_event"

  private val table = AuditTestTable("audit_sanitizer_spec", Table)

  private val at = Instant.parse("2026-11-04T10:00:00Z")

  private def event(id: UUID = UUID.randomUUID()) =
    AuditEvent(
      id = id,
      at = at,
      tenantId = None,
      source = AuditSource("svc"),
      nodeId = "node-1",
      kind = AuditKind("thing.done"),
      outcome = AuditOutcome.success,
      reason = None,
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
      details = Json.Obj(),
    )

  /** The outcome of writing one event as it is, in a transaction of its own: the SQLSTATEs of the failure, if there was one. */
  private def written(xa: TransactorZIO, e: AuditEvent): Task[Either[List[String], Unit]] =
    table.inTransaction(xa)(AuditInsert.insertBatch(List(e), table = Table)).either.map(_.left.map(AuditEvents.sqlStates))

  /** Each input the database refuses as it comes, with the SQLSTATE it refuses it by. */
  private val refused: List[(String, AuditEvent => AuditEvent, String)] = List(
    ("U+0000 in a text column", _.copy(tenantId = Some("ten\u0000ant")), "22021"),
    ("U+0000 in a text column of the kind", _.copy(reason = Some("\u0000")), "22021"),
    ("U+0000 in a JSON string of the details", _.copy(details = Json.Obj("k" -> Json.Str("v\u0000"))), "22P05"),
    ("U+0000 in a JSON key of the details", _.copy(details = Json.Obj("k\u0000" -> Json.Str("v"))), "22P05"),
    ("U+0000 in a JSON string deep in the details", _.copy(details = Json.Obj("a" -> Json.Arr(Json.Obj("b" -> Json.Str("\u0000"))))), "22P05"),
    ("text that is no address in ip", _.copy(ip = Some("not an address")), "22P02"),
    ("an address with too few parts", _.copy(ip = Some("1.2.3")), "22P02"),
    ("an address with a part past 255", _.copy(ip = Some("256.1.1.1")), "22P02"),
    ("an IPv6 address with nine groups", _.copy(ip = Some("1:2:3:4:5:6:7:8:9")), "22P02"),
    ("an IPv6 address with two gaps", _.copy(ip = Some("1::2::3")), "22P02"),
    ("an IPv4-mapped address with a leading zero", _.copy(ip = Some("::ffff:01.2.3.4")), "22P02"),
  )

  /** Inputs whose fate in the database is not assumed: whatever it does with them as they come, the sanitizer's output must be
    * stored. */
  private val odd: List[String] = List(
    "::",
    "::1",
    "::ffff:1.2.3.4",
    "::ffff:01.2.3.4",
    "01.2.3.4",
    "1.2.3.04",
    "0.0.0.0",
    "255.255.255.255",
    "1.2.3.4/24",
    "fe80::1%eth0",
    "2001:db8::",
    "1:2:3:4:5:6:7:8",
    "::١",
    "١٢٣.٤.٥.٦",
    "１.２.３.４",
    "1.2.3.4 ",
    " 1.2.3.4",
    "1.2.3.4\n",
    "",
    "::g",
    "a" * 100,
    "1" * 100,
  )

  private val alphabet: Gen[Any, Char] =
    Gen.oneOf(
      Gen.const('\u0000'),
      Gen.const('\uD83D'),
      Gen.const('\uDE00'),
      Gen.const('"'),
      Gen.const('\\'),
      Gen.const('\''),
      Gen.const('\n'),
      Gen.const('١'),
      Gen.const('é'),
      Gen.const(':'),
      Gen.const('.'),
      Gen.const('%'),
      Gen.const('/'),
      Gen.const(' '),
      Gen.char('0', '9'),
      Gen.char('a', 'f'),
      Gen.char('\u0001', '\u001f'),
      Gen.unicodeChar,
    )

  private val text: Gen[Any, String] = Gen.stringBounded(0, 40)(alphabet)

  private val address: Gen[Any, String] =
    Gen.stringBounded(0, 45)(Gen.oneOf(Gen.char('0', '9'), Gen.const('.'), Gen.const(':'), Gen.char('a', 'f'), Gen.const('١'), Gen.const('%')))

  private def jsonOf(depth: Int): Gen[Any, Json] =
    val leaf: Gen[Any, Json] = Gen.oneOf(text.map(Json.Str(_)), Gen.int.map(Json.Num(_)), Gen.boolean.map(Json.Bool(_)), Gen.const(Json.Null))
    if depth <= 0 then leaf
    else
      Gen.oneOf(
        leaf,
        Gen.listOfBounded(0, 4)(jsonOf(depth - 1)).map(items => Json.Arr(Chunk.fromIterable(items))),
        Gen.listOfBounded(0, 4)(text.zip(jsonOf(depth - 1))).map(fields => Json.Obj(Chunk.fromIterable(fields))),
      )

  private val anyEvent: Gen[Any, AuditEvent] =
    for
      tenant <- Gen.option(text)
      reason <- Gen.option(text)
      actor <- Gen.option(text)
      client <- Gen.option(text)
      session <- Gen.option(text)
      agent <- Gen.option(Gen.stringBounded(0, 700)(alphabet))
      trace <- Gen.option(text)
      targetType <- Gen.option(text)
      targetId <- Gen.option(Gen.stringBounded(0, 400)(alphabet))
      ip <- Gen.option(address)
      details <- Gen.listOfBounded(0, 5)(text.zip(jsonOf(3))).map(fields => Json.Obj(Chunk.fromIterable(fields)))
    yield event().copy(
      tenantId = tenant,
      reason = reason,
      actorId = actor,
      clientId = client,
      sessionId = session,
      userAgent = agent,
      traceId = trace,
      targetType = targetType,
      targetId = targetId,
      ip = ip,
      details = details,
    )

  val spec = suite("AuditSanitizer against Postgres")(
    suite("what the database refuses as it comes")(
      refused.map: (name, change, state) =>
        test(s"$name is refused with SQLSTATE $state, and stored once it has been through the sanitizer") {
          val raw = change(event())
          for
            xa <- ZIO.service[TransactorZIO]
            before <- written(xa, raw)
            after <- written(xa, AuditSanitizer(raw))
          yield assertTrue(before == Left(List(state)) || before.left.exists(_.contains(state)), after == Right(()))
        },
    ),
    suite("what the sanitizer lets through")(
      odd.map: candidate =>
        test(s"an ip of ${candidate.take(20).replace("\u0000", "NUL")} (length ${candidate.length}) is stored, or dropped, never refused") {
          for
            xa <- ZIO.service[TransactorZIO]
            after <- written(xa, AuditSanitizer(event().copy(ip = Some(candidate))))
          yield assertTrue(after == Right(()))
        },
    ),
    test("an event of random texts, addresses and nested details is stored once it has been through the sanitizer") {
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- check(anyEvent): raw =>
          written(xa, AuditSanitizer(raw)).map(outcome => assertTrue(outcome == Right(())))
      yield assertTrue(true)
    },
    test("a sanitized event is stored with what it holds, and the details are the JSON the database parsed") {
      val raw = event().copy(
        tenantId = Some("ten\u0000ant"),
        details = Json.Obj("k\u0000" -> Json.Str("v\u0000"), "n" -> Json.Arr(Json.Num(1), Json.Null)),
        ip = Some("::ffff:1.2.3.4"),
      )
      for
        xa <- ZIO.service[TransactorZIO]
        _ <- table.inTransaction(xa)(AuditInsert.insertBatch(List(AuditSanitizer(raw)), table = Table))
        stored <- table.rows(xa).map(_.head)
      yield assertTrue(stored.tenantId == Some("tenant"), stored.ip.isDefined, stored.details.contains("\"k\": \"v\""))
    },
  ) @@ TestAspect.sequential @@ TestAspect.before(table.reset) @@ TestAspect.timeout(120.seconds)
