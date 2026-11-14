package versola.central.configuration.partners

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** The requests partners make and the operator's decisions: listed newest first by the operator (per tenant, status) and by the
  * partner, and decided exactly once. */
trait PartnerApprovalRepositorySpec extends DatabaseSpecBase[PartnerApprovalRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantA = TenantId("tenant-a")
  val tenantB = TenantId("tenant-b")
  val acme = PartnerId("acme")
  val globex = PartnerId("globex")
  val now = Instant.parse("2026-11-10T10:00:00Z")

  def approval(id: String, partnerId: PartnerId, at: Instant, kind: ApprovalKind = ApprovalKind.ClientCreate): PartnerApprovalRecord =
    PartnerApprovalRecord(
      id = ApprovalId(id),
      partnerId = partnerId,
      kind = kind,
      targetId = s"$partnerId-shop",
      requested = Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("Shop")), "scope" -> Json.Arr(Json.Str("openid"))),
      current = Option.when(kind != ApprovalKind.ClientCreate)(Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("Old")))),
      applied = None,
      outsideLimits = List("clientName", "logoUri"),
      requestedBy = "staff-1",
      requestedAt = at,
      status = ApprovalStatus.Waiting,
      decidedBy = None,
      decidedAt = None,
      note = None,
    )

  override def testCases(env: PartnerApprovalRepositorySpec.Env) =
    List(
      test("requests are listed newest first, by tenant, by status and by partner") {
        val first = approval("a-1", acme, now)
        val second = approval("a-2", acme, now.plusSeconds(60), ApprovalKind.ClientChange)
        val other = approval("g-1", globex, now.plusSeconds(30))
        for
          _ <- env.fixtures.partner(acme, tenantA)
          _ <- env.fixtures.partner(globex, tenantB)
          _ <- env.repository.create(first)
          _ <- env.repository.create(second)
          _ <- env.repository.create(other)
          _ <- env.repository.decide(ApprovalId("a-1"), ApprovalStatus.Rejected, Some("admin-1"), now.plusSeconds(90), Some("no"), None)
          all <- env.repository.list(None, None, None, 100)
          tenant <- env.repository.list(Some(tenantA), None, None, 100)
          waiting <- env.repository.list(None, Some(ApprovalStatus.Waiting), None, 100)
          ofGlobex <- env.repository.list(None, None, Some(globex), 100)
          waitingFor <- env.repository.waitingFor(acme, ApprovalKind.aboutClients, s"$acme-shop")
          found <- env.repository.find(ApprovalId("a-1"))
        yield assertTrue(
          all.map(_.id) == Vector(ApprovalId("a-2"), ApprovalId("g-1"), ApprovalId("a-1")),
          tenant.map(_.id) == Vector(ApprovalId("a-2"), ApprovalId("a-1")),
          waiting.map(_.id) == Vector(ApprovalId("a-2"), ApprovalId("g-1")),
          ofGlobex == Vector(other),
          waitingFor.map(_.id) == Vector(ApprovalId("a-2")),
          found == Some(first.copy(
            status = ApprovalStatus.Rejected,
            decidedBy = Some("admin-1"),
            decidedAt = Some(now.plusSeconds(90)),
            note = Some("no"),
          )),
        )
      },
      test("a request is decided once: a second decision changes nothing, and `requested` is never rewritten") {
        val request = approval("a-1", acme, now)
        val applied = Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("Shop by ACME")))
        for
          _ <- env.fixtures.partner(acme, tenantA)
          _ <- env.repository.create(request)
          first <- env.repository.decide(ApprovalId("a-1"), ApprovalStatus.Approved, Some("admin-1"), now.plusSeconds(10), None, Some(applied))
          second <- env.repository.decide(ApprovalId("a-1"), ApprovalStatus.Rejected, Some("admin-2"), now.plusSeconds(20), Some("late"), None)
          unknown <- env.repository.decide(ApprovalId("nobody"), ApprovalStatus.Approved, None, now, None, None)
          found <- env.repository.find(ApprovalId("a-1"))
        yield assertTrue(
          first,
          !second,
          !unknown,
          found.map(_.status) == Some(ApprovalStatus.Approved),
          found.map(_.applied) == Some(Some(applied)),
          found.map(_.requested) == Some(request.requested),
          found.flatMap(_.decidedBy) == Some("admin-1"),
        )
      },
      test("a listing is bounded: the newest `limit`, and the requests about one target alone") {
        for
          _ <- env.fixtures.partner(acme, tenantA)
          _ <- ZIO.foreachDiscard(1 to 5)(n => env.repository.create(approval(s"a-$n", acme, now.plusSeconds(n.toLong))))
          _ <- env.repository.create(approval("a-other", acme, now.plusSeconds(60)).copy(targetId = "acme-pay"))
          newest <- env.repository.list(None, None, Some(acme), 2)
          forShop <- env.repository.forTarget(acme, ApprovalKind.aboutClients, s"$acme-shop", 3)
          forPay <- env.repository.forTarget(acme, ApprovalKind.aboutClients, "acme-pay", 3)
        yield assertTrue(
          newest.map(_.id) == Vector(ApprovalId("a-other"), ApprovalId("a-5")),
          forShop.map(_.id) == Vector(ApprovalId("a-5"), ApprovalId("a-4"), ApprovalId("a-3")),
          forPay.map(_.id) == Vector(ApprovalId("a-other")),
        )
      },
      // #962 review: the check "already waiting" and the insert are one step under the partner's lock, whatever is requested at once
      test("of requests made at once about one target, one is queued and the others are told so; another target or another partner is free") {
        def change(n: Int, partner: PartnerId = acme, target: String = "acme-shop") =
          approval(s"c-$n", partner, now.plusSeconds(n.toLong), ApprovalKind.ClientChange).copy(targetId = target)
        for
          _ <- env.fixtures.partner(acme, tenantA)
          _ <- env.fixtures.partner(globex, tenantB)
          results <- ZIO.foreachPar(1 to 8)(n => env.repository.createIfNoneWaiting(change(n), ApprovalKind.aboutClients))
          other <- env.repository.createIfNoneWaiting(change(20, target = "acme-pay"), ApprovalKind.aboutClients)
          foreign <- env.repository.createIfNoneWaiting(change(21, partner = globex, target = "acme-shop"), ApprovalKind.aboutClients)
          stored <- env.repository.waitingFor(acme, ApprovalKind.aboutClients, "acme-shop")
          // once it is decided, the target is free again
          _ <- env.repository.decide(stored.head.id, ApprovalStatus.Rejected, Some("admin-1"), now.plusSeconds(99), None, None)
          again <- env.repository.createIfNoneWaiting(change(30), ApprovalKind.aboutClients)
        yield assertTrue(results.count(identity) == 1, other, foreign, stored.size == 1, again)
      },
      // the request about the partner and the requests about its clients are separate namespaces: a client may be called as the partner is
      test("a profile change of a partner and a request about a client that has the partner's id do not see each other") {
        val profile = approval("p-1", acme, now, ApprovalKind.ProfileChange).copy(targetId = acme)
        val clientCall = approval("p-2", acme, now.plusSeconds(1), ApprovalKind.ClientChange).copy(targetId = acme)
        for
          _ <- env.fixtures.partner(acme, tenantA)
          queuedProfile <- env.repository.createIfNoneWaiting(profile, ApprovalKind.aboutPartner)
          queuedClient <- env.repository.createIfNoneWaiting(clientCall, ApprovalKind.aboutClients)
          waitingProfile <- env.repository.waitingFor(acme, ApprovalKind.aboutPartner, acme)
          waitingClient <- env.repository.waitingFor(acme, ApprovalKind.aboutClients, acme)
          historyOfClient <- env.repository.forTarget(acme, ApprovalKind.aboutClients, acme, 10)
        yield assertTrue(
          queuedProfile,
          queuedClient,
          waitingProfile.map(_.id) == Vector(ApprovalId("p-1")),
          waitingClient.map(_.id) == Vector(ApprovalId("p-2")),
          historyOfClient.map(_.id) == Vector(ApprovalId("p-2")),
        )
      },
      // #962 review: two decisions of one request made at once (two operators, or an approval and a withdrawal) are one decision
      test("of decisions made at once about one request, one wins and the others are told it was decided") {
        val request = approval("a-1", acme, now)
        for
          _ <- env.fixtures.partner(acme, tenantA)
          _ <- env.repository.create(request)
          outcomes <- ZIO.foreachPar(List(
            ApprovalStatus.Approved,
            ApprovalStatus.Rejected,
            ApprovalStatus.Withdrawn,
            ApprovalStatus.Approved,
            ApprovalStatus.Rejected,
            ApprovalStatus.Withdrawn,
          ))(status => env.repository.decide(request.id, status, Some("admin-1"), now.plusSeconds(10), None, None).map(status -> _))
          decided <- env.repository.find(request.id)
          winners = outcomes.collect { case (status, true) => status }
        yield assertTrue(winners.size == 1, decided.map(_.status) == winners.headOption)
      },
      test("a decision that could not be carried out is taken back, once, and only from the status it was made in") {
        val request = approval("a-1", acme, now)
        for
          _ <- env.fixtures.partner(acme, tenantA)
          _ <- env.repository.create(request)
          wrong <- env.repository.reopen(ApprovalId("a-1"), ApprovalStatus.Approved)
          _ <- env.repository.decide(
            ApprovalId("a-1"),
            ApprovalStatus.Approved,
            Some("admin-1"),
            now.plusSeconds(10),
            Some("ok"),
            Some(Json.Obj("scope" -> Json.Arr())),
          )
          wrongStatus <- env.repository.reopen(ApprovalId("a-1"), ApprovalStatus.Rejected)
          reopened <- env.repository.reopen(ApprovalId("a-1"), ApprovalStatus.Approved)
          twice <- env.repository.reopen(ApprovalId("a-1"), ApprovalStatus.Approved)
          found <- env.repository.find(ApprovalId("a-1"))
          decidedAgain <- env.repository.decide(ApprovalId("a-1"), ApprovalStatus.Rejected, Some("admin-2"), now.plusSeconds(20), Some("no"), None)
        yield assertTrue(
          !wrong,
          !wrongStatus,
          reopened,
          !twice,
          found == Some(request),
          // waiting again, so it can be decided once more
          decidedAgain,
        )
      },
    )

object PartnerApprovalRepositorySpec:
  /** The partner a request belongs to, which the repository under test does not write. */
  trait Fixtures:
    def partner(id: PartnerId, tenantId: TenantId): Task[Unit]

  case class Env(repository: PartnerApprovalRepository, fixtures: Fixtures)
