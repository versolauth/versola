package versola.central.audit

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.audit.CentralAuditRepository.Key
import versola.util.DatabaseSpecBase
import versola.util.audit.*
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Conformance suite for any [[CentralAuditRepository]]; see `PostgresAuditEventRepositorySpec` in `central-postgres-impl` for the
  * one binding. */
trait CentralAuditRepositorySpec extends DatabaseSpecBase[CentralAuditRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  import CentralAuditRepositorySpec.{oldAt, oldDay, written}

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
    source = CentralAudit.Source,
    nodeId = "node-1",
    kind = AuditKind(kind),
    outcome = AuditOutcome.success,
    reason = None,
    severity = AuditSeverity.info,
    actorType = CentralAudit.Actors.Admin,
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
      test("the rows of a partner are those whose details name it, newest first, narrowed by actor, kind and text, and paged by key") {
        def about(n: Int, partner: String, actor: String, kind: String, target: String, at: Instant) =
          event(n, at = at, userId = None).copy(
            kind = AuditKind(kind),
            actorType = CentralAudit.Actors.PartnerStaff,
            actorId = Some(actor),
            targetId = Some(target),
            targetType = None,
            details = Json.Obj("partnerId" -> Json.Str(partner)),
          )
        val first = about(11, "acme", "partner-staff:alice", "partner.client.create", "acme-app", day)
        val second =
          about(12, "acme", "partner-staff:bob", "partner.client.update", "acme-web", day.plusSeconds(60))
        val third =
          about(13, "acme", "partner-staff:alice", "partner.client.update", "acme-app", day.plusSeconds(120))
        val foreign = about(
          14,
          "globex",
          "partner-staff:carol",
          "partner.client.create",
          "globex-app",
          day.plusSeconds(180),
        )
        val unrelated = event(15, userId = None)
        for
          _ <- env.repository.insertBatch(List(first, second, third, foreign, unrelated))
          all <- env.repository.partnerEvents("acme", None, None, None, None, 10)
          byActor <- env.repository.partnerEvents("acme", None, Some("partner-staff:alice"), None, None, 10)
          byKind <- env.repository.partnerEvents("acme", None, None, Some("partner.client.create"), None, 10)
          byText <- env.repository.partnerEvents("acme", None, None, None, Some("acme-web"), 10)
          page <- env.repository.partnerEvents("acme", None, None, None, None, 2)
          next <- env.repository.partnerEvents("acme", Some(CentralAuditRepository.Key(page.last.at, page.last.id)), None, None, None, 2)
          nobody <- env.repository.partnerEvents("initech", None, None, None, None, 10)
        yield assertTrue(
          all.map(_.id) == List(third.id, second.id, first.id),
          byActor.map(_.id) == List(third.id, first.id),
          byKind.map(_.id) == List(first.id),
          byText.map(_.id) == List(second.id),
          page.map(_.id) == List(third.id, second.id),
          next.map(_.id) == List(first.id),
          nobody.isEmpty,
        )
      },
      test("a partner's search text is text, not a pattern, and the operator's staff are not found through it") {
        def about(n: Int, actorType: versola.util.audit.ActorType, actor: String, target: String, at: Instant) =
          event(n, at = at, userId = None).copy(
            kind = AuditKind("partner.client.create"),
            actorType = actorType,
            actorId = Some(actor),
            targetId = Some(target),
            targetType = None,
            details = Json.Obj("partnerId" -> Json.Str("acme")),
          )
        val percent = about(21, CentralAudit.Actors.PartnerStaff, "partner-staff:alice", "100%-shop", day)
        val plain =
          about(22, CentralAudit.Actors.PartnerStaff, "partner-staff:bob", "1000-shop", day.plusSeconds(1))
        val underscore =
          about(23, CentralAudit.Actors.PartnerStaff, "partner-staff:carol", "a_b", day.plusSeconds(2))
        val other = about(24, CentralAudit.Actors.PartnerStaff, "partner-staff:dave", "axb", day.plusSeconds(3))
        val operator = about(25, CentralAudit.Actors.Admin, "secret-admin-id", "acme", day.plusSeconds(4))
        for
          _ <- env.repository.insertBatch(List(percent, plain, underscore, other, operator))
          byPercent <- env.repository.partnerEvents("acme", None, None, None, Some("%"), 10)
          byHundredPercent <- env.repository.partnerEvents("acme", None, None, None, Some("100%"), 10)
          byUnderscore <- env.repository.partnerEvents("acme", None, None, None, Some("a_b"), 10)
          byBang <- env.repository.partnerEvents("acme", None, None, None, Some("!"), 10)
          // the operator's administrator is neither found by name nor filtered by id: the partner may not probe for them
          byOperatorText <- env.repository.partnerEvents("acme", None, None, None, Some("secret-admin"), 10)
          byOperatorActor <- env.repository.partnerEvents("acme", None, Some("secret-admin-id"), None, None, 10)
          byStaff <- env.repository.partnerEvents("acme", None, Some("partner-staff:bob"), None, None, 10)
        yield assertTrue(
          byPercent.map(_.id) == List(percent.id),
          byHundredPercent.map(_.id) == List(percent.id),
          byUnderscore.map(_.id) == List(underscore.id),
          byBang.isEmpty,
          byOperatorText.isEmpty,
          byOperatorActor.isEmpty,
          byStaff.map(_.id) == List(plain.id),
        )
      },
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
      test("the administrators' changes to a user are the admin rows that are not a look or a query, however many of those there are") {
        val looks = (10 to 40).toList.map(n => event(n, at = day.plusSeconds(100 + n), kind = "admin.user.viewed"))
        val queries = (50 to 60).toList.map(n => event(n, at = day.plusSeconds(200 + n), kind = "admin.audit.queried"))
        for
          _ <- env.repository.insertBatch(
            looks ++ queries ++ List(
              event(1, at = day.plusSeconds(10), kind = "admin.user.update"),
              event(2, at = day.plusSeconds(20), kind = "admin.user-password.action:reset"),
              event(3, at = day.plusSeconds(30), kind = "admin.secret.revealed"),
              // not an administrator's: another source's kind about the same user
              event(4, at = day.plusSeconds(40), kind = "login.completed"),
              event(5, at = day.plusSeconds(25), userId = Some(other), kind = "admin.user.update"),
            ),
          )
          few <- env.repository.adminActions(user, wide._1, wide._2, 3)
          all <- env.repository.adminActions(user, wide._1, wide._2, 100)
        yield assertTrue(
          // newest first, and the bound counts changes only: the looks, newer than all of them, do not take its places
          few.map(_.id.toString.takeRight(1)) == List("3", "2", "1"),
          all.map(_.kind.value) == List("admin.secret.revealed", "admin.user-password.action:reset", "admin.user.update"),
        )
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
      test("a day whose rows sit in the default partition is reported and left alone, and is attached once they are pruned") {
        for
          _ <- env.fixtures.dropOldDayPartition
          _ <- env.repository.insertBatch(List(written("00000000-0000-7000-8000-000000000002")))
          held <- env.repository.maintainPartitions(oldDay, 1, LocalDate.of(2000, 1, 1))
          stillThere <- env.fixtures.countUnpartitioned
          pruned <- env.repository.pruneDefault(oldAt.plusSeconds(1))
          attached <- env.repository.maintainPartitions(oldDay, 1, LocalDate.of(2000, 1, 1))
          ahead <- env.repository.partitionDaysAhead(oldDay.minusDays(1))
          _ <- env.fixtures.dropOldDayPartition
        yield assertTrue(
          // never moved: an insert-only replication of the table would see the row twice
          held.daysLeftInDefault == List(oldDay),
          held.created == 0,
          held.movedFromDefault == 0L,
          stillThere == 1L,
          pruned == 1L,
          attached.daysLeftInDefault.isEmpty,
          attached.created == 1,
          ahead >= 1,
        )
      },
      test("the service is the owner of the table, so that it may maintain the partitions") {
        for ownership <- env.repository.ownership
        yield assertTrue(ownership.mayAlter, ownership.owner == ownership.role)
      },
    )

object CentralAuditRepositorySpec:
  case class Env(repository: CentralAuditRepository, fixtures: Fixtures)

  /** What a case reads and resets of the storage's partitions, which no method of the trait does: the storage's runner supplies it. */
  trait Fixtures:
    /** How many events are held for a day that has no partition (the default partition's rows). */
    def countUnpartitioned: Task[Long]

    /** Forgets the partition of [[oldDay]], if a case or an earlier run made one: its events are then held for a day without one. */
    def dropOldDayPartition: Task[Unit]

  /** A day no run of this spec or of the service has a partition for, so that its rows are in the default partition. */
  val oldDay: LocalDate = LocalDate.of(2001, 2, 3)
  val oldAt: Instant = Instant.parse("2001-02-03T10:00:00Z")

  def written(id: String): AuditEvent = AuditEvent(
    id = UUID.fromString(id),
    at = oldAt,
    tenantId = None,
    source = CentralAudit.Source,
    nodeId = "node-1",
    kind = CentralAudit.admin("tenant", "create"),
    outcome = AuditOutcome.success,
    reason = None,
    severity = AuditSeverity.info,
    actorType = CentralAudit.Actors.Admin,
    actorId = None,
    userId = Some(UUID.fromString("00000000-0000-0000-0000-000000000001")),
    clientId = None,
    sessionId = None,
    ip = None,
    userAgent = None,
    traceId = None,
    targetType = None,
    targetId = None,
    details = Json.Obj(),
  )
