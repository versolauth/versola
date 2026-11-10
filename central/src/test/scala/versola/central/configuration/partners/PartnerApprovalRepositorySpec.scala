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
          all <- env.repository.list(None, None, None)
          tenant <- env.repository.list(Some(tenantA), None, None)
          waiting <- env.repository.list(None, Some(ApprovalStatus.Waiting), None)
          ofGlobex <- env.repository.list(None, None, Some(globex))
          waitingFor <- env.repository.waitingFor(acme, s"$acme-shop")
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
    )

object PartnerApprovalRepositorySpec:
  /** The partner a request belongs to, which the repository under test does not write. */
  trait Fixtures:
    def partner(id: PartnerId, tenantId: TenantId): Task[Unit]

  case class Env(repository: PartnerApprovalRepository, fixtures: Fixtures)
