package versola.central.configuration.partners

import versola.central.configuration.tenants.TenantId
import zio.Task
import zio.json.ast.Json

import java.time.Instant

/** The requests partners make and the operator's decisions (`partner_approvals`). Read from the table, not cached: the
  * operator's queue and a partner's console are the only readers, and neither is on a hot path. */
trait PartnerApprovalRepository:
  def create(record: PartnerApprovalRecord): Task[Unit]

  def find(id: ApprovalId): Task[Option[PartnerApprovalRecord]]

  /** Newest first. Every filter narrows: `tenantId` to the partners of a tenant, `status`, `partnerId`. */
  def list(tenantId: Option[TenantId], status: Option[ApprovalStatus], partnerId: Option[PartnerId]): Task[Vector[PartnerApprovalRecord]]

  /** The waiting requests about `targetId` of `partnerId`, newest first. */
  def waitingFor(partnerId: PartnerId, targetId: String): Task[Vector[PartnerApprovalRecord]]

  /** Decides a waiting request: `false` when it is not waiting (or does not exist), and nothing is written then. */
  def decide(
      id: ApprovalId,
      status: ApprovalStatus,
      decidedBy: Option[String],
      decidedAt: Instant,
      note: Option[String],
      applied: Option[Json.Obj],
  ): Task[Boolean]
