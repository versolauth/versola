package versola.central.configuration.partners

import versola.central.configuration.tenants.TenantId
import zio.Task
import zio.json.ast.Json

import java.time.Instant

/** The requests partners make and the operator's decisions (`partner_approvals`). Read from the table, not cached: the
  * operator's queue and a partner's console are the only readers, and neither is on a hot path. */
trait PartnerApprovalRepository:
  def create(record: PartnerApprovalRecord): Task[Unit]

  /** Stores the request unless one of the same family (`kinds`: the requests about clients, or about the partner) is waiting about
    * the same target of the partner: `false` then, and nothing is written. The partner's row is locked for the check and the insert, so
    * two requests made at once cannot both find none waiting. */
  def createIfNoneWaiting(record: PartnerApprovalRecord, kinds: Set[ApprovalKind]): Task[Boolean]

  def find(id: ApprovalId): Task[Option[PartnerApprovalRecord]]

  /** Newest first, at most `limit` of them. Every filter narrows: `tenantId` to the partners of a tenant, `status`, `partnerId`. */
  def list(
      tenantId: Option[TenantId],
      status: Option[ApprovalStatus],
      partnerId: Option[PartnerId],
      limit: Int,
  ): Task[Vector[PartnerApprovalRecord]]

  /** The requests of the kinds `kinds` about one target of a partner, newest first, at most `limit` of them. */
  def forTarget(partnerId: PartnerId, kinds: Set[ApprovalKind], targetId: String, limit: Int): Task[Vector[PartnerApprovalRecord]]

  /** The waiting requests of the kinds `kinds` about `targetId` of `partnerId`, newest first. */
  def waitingFor(partnerId: PartnerId, kinds: Set[ApprovalKind], targetId: String): Task[Vector[PartnerApprovalRecord]]

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
