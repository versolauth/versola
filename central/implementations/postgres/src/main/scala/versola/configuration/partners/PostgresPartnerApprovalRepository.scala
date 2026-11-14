package versola.configuration.partners

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.partners.{ApprovalId, ApprovalKind, ApprovalStatus, PartnerApprovalRecord, PartnerApprovalRepository, PartnerId}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Task, ZLayer}

import java.time.Instant

class PostgresPartnerApprovalRepository(xa: TransactorZIO) extends PartnerApprovalRepository, BasicCodecs:
  given DbCodec[ApprovalId] = DbCodec.StringCodec.biMap(ApprovalId(_), identity[String])
  given DbCodec[PartnerId] = DbCodec.StringCodec.biMap(PartnerId(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ApprovalKind] =
    DbCodec.StringCodec.biMap(code => ApprovalKind.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[ApprovalStatus] =
    DbCodec.StringCodec.biMap(code => ApprovalStatus.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[Json.Obj] = jsonBCodec[Json.Obj]
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PartnerApprovalRecord] = DbCodec.derived

  private val columns = SqlLiteral(
    "a.id, a.partner_id, a.kind, a.target_id, a.requested, a.current, a.applied, a.outside_limits, a.requested_by, a.requested_at, " +
      "a.status, a.decided_by, a.decided_at, a.note",
  )

  override def create(record: PartnerApprovalRecord): Task[Unit] =
    xa.connectMeasured("create-partner-approval"):
      sql"""
        INSERT INTO partner_approvals (id, partner_id, kind, target_id, requested, current, applied, outside_limits, requested_by,
                                       requested_at, status, decided_by, decided_at, note)
        VALUES (${record.id}, ${record.partnerId}, ${record.kind}, ${record.targetId}, ${record.requested}, ${record.current},
                ${record.applied}, ${record.outsideLimits}, ${record.requestedBy}, ${record.requestedAt}, ${record.status},
                ${record.decidedBy}, ${record.decidedAt}, ${record.note})
      """.update.run()
    .unit

  private def kindsOf(kinds: Set[ApprovalKind]): SqlLiteral = SqlLiteral(kinds.toList.map(kind => s"'${kind.code}'").sorted.mkString(", "))

  override def createIfNoneWaiting(record: PartnerApprovalRecord, kinds: Set[ApprovalKind]): Task[Boolean] =
    xa.transactMeasured("create-partner-approval-if-none-waiting"):
      // The partner's row is locked (`FOR NO KEY UPDATE`, which the foreign keys that point at it do not wait on) so that two requests of
      // one partner run one after the other and the second sees the first. The decision is made here, not in the database.
      sql"SELECT id FROM partners WHERE id = ${record.partnerId} FOR NO KEY UPDATE".query[PartnerId].run()
      val kindList = kindsOf(kinds)
      val waiting =
        sql"""
          SELECT COUNT(*) FROM partner_approvals a
          WHERE a.partner_id = ${record.partnerId} AND a.target_id = ${record.targetId} AND a.status = 'waiting' AND a.kind IN ($kindList)
        """.query[Long].run().head
      if waiting > 0 then false
      else
        sql"""
          INSERT INTO partner_approvals (id, partner_id, kind, target_id, requested, current, applied, outside_limits, requested_by,
                                         requested_at, status, decided_by, decided_at, note)
          VALUES (${record.id}, ${record.partnerId}, ${record.kind}, ${record.targetId}, ${record.requested}, ${record.current},
                  ${record.applied}, ${record.outsideLimits}, ${record.requestedBy}, ${record.requestedAt}, ${record.status},
                  ${record.decidedBy}, ${record.decidedAt}, ${record.note})
        """.update.run()
        true

  override def find(id: ApprovalId): Task[Option[PartnerApprovalRecord]] =
    xa.connectMeasured("find-partner-approval"):
      sql"SELECT $columns FROM partner_approvals a WHERE a.id = $id".query[PartnerApprovalRecord].run().headOption

  override def list(
      tenantId: Option[TenantId],
      status: Option[ApprovalStatus],
      partnerId: Option[PartnerId],
      limit: Int,
  ): Task[Vector[PartnerApprovalRecord]] =
    xa.connectMeasured("list-partner-approvals"):
      // Each filter is written as "absent or equal", so one statement serves every combination.
      sql"""
        SELECT $columns
        FROM partner_approvals a
        JOIN partners p ON p.id = a.partner_id
        WHERE (${tenantId.isEmpty} OR p.tenant_id = ${tenantId.getOrElse(TenantId(""))})
          AND (${status.isEmpty} OR a.status = ${status.getOrElse(ApprovalStatus.Waiting)})
          AND (${partnerId.isEmpty} OR a.partner_id = ${partnerId.getOrElse(PartnerId(""))})
        ORDER BY a.requested_at DESC, a.id DESC
        LIMIT $limit
      """.query[PartnerApprovalRecord].run()

  override def forTarget(
      partnerId: PartnerId,
      kinds: Set[ApprovalKind],
      targetId: String,
      limit: Int,
  ): Task[Vector[PartnerApprovalRecord]] =
    xa.connectMeasured("partner-approvals-for-target"):
      val kindList = kindsOf(kinds)
      sql"""
        SELECT $columns FROM partner_approvals a
        WHERE a.partner_id = $partnerId AND a.target_id = $targetId AND a.kind IN ($kindList)
        ORDER BY a.requested_at DESC, a.id DESC
        LIMIT $limit
      """.query[PartnerApprovalRecord].run()

  override def waitingFor(partnerId: PartnerId, kinds: Set[ApprovalKind], targetId: String): Task[Vector[PartnerApprovalRecord]] =
    xa.connectMeasured("waiting-partner-approvals-for"):
      val kindList = kindsOf(kinds)
      sql"""
        SELECT $columns FROM partner_approvals a
        WHERE a.partner_id = $partnerId AND a.target_id = $targetId AND a.status = 'waiting' AND a.kind IN ($kindList)
        ORDER BY a.requested_at DESC, a.id DESC
      """.query[PartnerApprovalRecord].run()

  override def reopen(id: ApprovalId, from: ApprovalStatus): Task[Boolean] =
    xa.connectMeasured("reopen-partner-approval"):
      sql"""
        UPDATE partner_approvals
        SET status = 'waiting', decided_by = NULL, decided_at = NULL, note = NULL, applied = NULL
        WHERE id = $id AND status = $from
      """.update.run() > 0

  override def decide(
      id: ApprovalId,
      status: ApprovalStatus,
      decidedBy: Option[String],
      decidedAt: Instant,
      note: Option[String],
      applied: Option[Json.Obj],
  ): Task[Boolean] =
    xa.connectMeasured("decide-partner-approval"):
      sql"""
        UPDATE partner_approvals
        SET status = $status, decided_by = $decidedBy, decided_at = $decidedAt, note = $note, applied = $applied
        WHERE id = $id AND status = 'waiting'
      """.update.run() > 0

object PostgresPartnerApprovalRepository:
  def live: ZLayer[TransactorZIO, Nothing, PartnerApprovalRepository] =
    ZLayer.fromFunction(PostgresPartnerApprovalRepository(_))
