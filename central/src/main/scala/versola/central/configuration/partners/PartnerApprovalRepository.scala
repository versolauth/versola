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

  /** Newest first, at most `limit` of them. Every filter narrows: `tenantId` to the partners of a tenant, `status`, `partnerId`. */
  def list(
      tenantId: Option[TenantId],
      status: Option[ApprovalStatus],
      partnerId: Option[PartnerId],
      limit: Int,
  ): Task[Vector[PartnerApprovalRecord]]

  /** The requests about one target of a partner, newest first, at most `limit` of them. */
  def forTarget(partnerId: PartnerId, targetId: String, limit: Int): Task[Vector[PartnerApprovalRecord]]

  /** The waiting requests about `targetId` of `partnerId`, newest first. */
  def waitingFor(partnerId: PartnerId, targetId: String): Task[Vector[PartnerApprovalRecord]]

  /** Takes a decision back: an `approved` request is `waiting` again, undecided, when applying it failed. `false` when it is not
    * in the status `from`. */
  def reopen(id: ApprovalId, from: ApprovalStatus): Task[Boolean]

  /** Decides a waiting request: `false` when it is not waiting (or does not exist), and nothing is written then. */
  def decide(
      id: ApprovalId,
      status: ApprovalStatus,
      decidedBy: Option[String],
      decidedAt: Instant,
      note: Option[String],
      applied: Option[Json.Obj],
  ): Task[Boolean]
