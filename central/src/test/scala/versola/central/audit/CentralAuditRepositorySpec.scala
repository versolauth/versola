package versola.central.audit

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.audit.CentralAuditRepository.Key
import versola.util.DatabaseSpecBase
import versola.util.audit.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

/** Conformance suite for any [[CentralAuditRepository]]; see `PostgresAuditEventRepositorySpec` in `central-postgres-impl` for the
  * one binding. */
trait CentralAuditRepositorySpec extends DatabaseSpecBase[CentralAuditRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val user = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val day = Instant.parse("2026-11-03T00:00:00Z")

  private def event(
      n: Int,
      at: Instant = day,
      userId: Option[UUID] = Some(user),
      kind: String = "admin.user.update",
  ) = AuditEvent(
    id = UUID.fromString(f"00000000-0000-7000-8000-$n%012d"),
    at = at,
    tenantId = Some("default"),
    source = AuditSource.central,
    nodeId = "node-1",
    kind = AuditKind(kind),
    outcome = AuditOutcome.success,
    reason = None,
    severity = AuditSeverity.info,
    actorType = ActorType.admin,
    actorId = Some("00000000-0000-0000-0000-0000000000f1"),
    userId = userId,
    clientId = None,
    sessionId = None,
    ip = None,
    userAgent = None,
    traceId = None,
    targetType = Some("user"),
    targetId = userId.map(_.toString),
    details = Json.Obj("path" -> Json.Str("/users"), "status" -> Json.Num(200)),
  )

  private val wide = (Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z"))

  override def testCases(env: CentralAuditRepositorySpec.Env) =
    List(
      test("every field of an event comes back as it was written, the address and the payload included") {
        val written = event(1).copy(
          reason = Some("http_409"),
          severity = AuditSeverity.high,
          outcome = AuditOutcome.denied,
          clientId = Some("central-admin"),
          sessionId = Some("s1"),
          ip = Some("2001:db8::1"),
          userAgent = Some("Console/1"),
          traceId = Some("0af7651916cd43dd8448eb211c80319c"),
          details = Json.Obj("changed" -> Json.Arr(Json.Str("clientName")), "query" -> Json.Obj("clientId" -> Json.Str("c1"))),
        )
        for
          _ <- env.repository.insertBatch(List(written))
          read <- env.repository.userEvents(user, wide._1, wide._2, None, 10)
        yield assertTrue(read == List(written))
      },
      test("an address of the old form is read back as written") {
        val written = event(2).copy(ip = Some("10.1.2.3"))
        for
          _ <- env.repository.insertBatch(List(written))
          read <- env.repository.userEvents(user, wide._1, wide._2, None, 10)
        yield assertTrue(read.map(_.ip) == List(Some("10.1.2.3")))
      },
      test("the rows of the user, newest first, and not another user's or a row about nobody") {
        for
          _ <- env.repository.insertBatch(
            List(
              event(1, at = day.plusSeconds(10)),
              event(2, at = day.plusSeconds(30)),
              event(3, at = day.plusSeconds(20), userId = Some(other)),
              event(4, at = day.plusSeconds(40), userId = None),
              event(5, at = day.plusSeconds(20)),
            ),
          )
          read <- env.repository.userEvents(user, wide._1, wide._2, None, 10)
        yield assertTrue(read.map(_.id.toString.takeRight(1)) == List("2", "5", "1"))
      },
      test("the window is from inclusive and until exclusive") {
        for
          _ <- env.repository.insertBatch(List(event(1, at = day), event(2, at = day.plusSeconds(60)), event(3, at = day.plusSeconds(120))))
          read <- env.repository.userEvents(user, day, day.plusSeconds(120), None, 10)
          none <- env.repository.userEvents(user, day.plusSeconds(121), day.plusSeconds(300), None, 10)
        yield assertTrue(read.map(_.id.toString.takeRight(1)) == List("2", "1"), none.isEmpty)
      },
      test("a page is continued from the key of its last row, and rows of one instant are neither lost nor repeated") {
        val same = day.plusSeconds(5)
        for
          _ <- env.repository.insertBatch((1 to 5).toList.map(n => event(n, at = same)) :+ event(6, at = day.plusSeconds(1)))
          first <- env.repository.userEvents(user, wide._1, wide._2, None, 2)
          second <- env.repository.userEvents(user, wide._1, wide._2, first.lastOption.map(e => Key(e.at, e.id)), 2)
          third <- env.repository.userEvents(user, wide._1, wide._2, second.lastOption.map(e => Key(e.at, e.id)), 2)
          fourth <- env.repository.userEvents(user, wide._1, wide._2, third.lastOption.map(e => Key(e.at, e.id)), 2)
        yield
          val all = (first ++ second ++ third).map(_.id.toString.takeRight(1))
          assertTrue(all == List("5", "4", "3", "2", "1", "6"), fourth.isEmpty)
      },
      test("nothing written is an empty batch, and an empty window is an empty page") {
        for
          _ <- env.repository.insertBatch(Nil)
          read <- env.repository.userEvents(user, wide._1, wide._2, None, 10)
        yield assertTrue(read.isEmpty)
      },
    )

object CentralAuditRepositorySpec:
  case class Env(repository: CentralAuditRepository)
