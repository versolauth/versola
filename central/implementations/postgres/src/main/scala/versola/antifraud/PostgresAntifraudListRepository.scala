package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.antifraud.{
  AntifraudListAction,
  AntifraudListRepository,
  AntifraudListType,
  AntifraudValueType,
  AuditRecordResponse,
  ListEntryFilter,
  ListEntryRecord,
  PolicyLists,
  PreviousListValuesResponse,
}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresAntifraudListRepository(xa: TransactorZIO) extends AntifraudListRepository, BasicCodecs:
  import PostgresAntifraudListRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private def audited(action: AntifraudListAction, actor: String, at: Instant, entry: EntryRow, previous: Option[EntryRow])(using DbCon): Unit =
    sql"""
      INSERT INTO antifraud_list_audit (id, at, action, actor, entry_id, type, value_type, value, tenant_id, expires_at, reason,
                                        previous_expires_at, previous_reason, previous_source, previous_shadow)
      VALUES (${UUID.randomUUID()}, $at, ${action.toString}, $actor, ${entry.id}, ${entry.listType}, ${entry.valueType},
              ${entry.value}, ${entry.tenantId}, ${entry.expiresAt}, ${entry.reason},
              ${previous.flatMap(_.expiresAt)}, ${previous.flatMap(_.reason)}, ${previous.map(_.source)}, ${previous.map(_.shadow)})
    """.update.run()
    ()

  /** Moves the version and writes the change that made it, in the caller's transaction. Concurrent bumps wait on the
    * version row, so versions are handed out and committed in order. */
  private def bumped(entryId: UUID, removed: Boolean, at: Instant)(using DbCon): Unit =
    val version = sql"UPDATE antifraud_list_version SET version = version + 1 WHERE id = 1 RETURNING version".query[Long].run().head
    sql"INSERT INTO antifraud_list_changes (version, entry_id, removed, at) VALUES ($version, $entryId, $removed, $at)".update.run()
    ()

  private def currentVersion()(using DbCon): Long =
    sql"SELECT version FROM antifraud_list_version WHERE id = 1".query[Long].run().head

  override def version: Task[Long] =
    xa.connectMeasured("antifraud-list-version")(currentVersion())

  override def upsert(entry: ListEntryRecord, actor: String, at: Instant): Task[ListEntryRecord] =
    xa.transactMeasured("upsert-antifraud-list-entry"):
      // The entry as it is now, locked, to say what the update changed. A concurrent first insert of the same value that
      // wins the race after this read is audited as a create: the entry is the same, only the record is less exact.
      val tenant = entry.tenantId.map(identity[String])
      val existing = sql"""
        SELECT $EntryColumns FROM antifraud_list_entries
        WHERE type = ${entry.listType.toString} AND value_type = ${entry.valueType.toString} AND value = ${entry.value}
          AND COALESCE(tenant_id, '') = ${tenant.getOrElse("")}
        FOR UPDATE
      """.query[EntryRow].run().headOption
      val stored = sql"""
        INSERT INTO antifraud_list_entries (id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at)
        VALUES (${entry.id}, ${entry.listType.toString}, ${entry.valueType.toString}, ${entry.value}, $tenant, ${entry.expiresAt},
                ${entry.reason}, ${entry.source}, ${entry.shadow}, ${entry.createdBy}, ${entry.createdAt})
        ON CONFLICT (type, value_type, value, COALESCE(tenant_id, '')) DO UPDATE
          SET expires_at = EXCLUDED.expires_at, reason = EXCLUDED.reason, source = EXCLUDED.source,
              shadow = EXCLUDED.shadow
        RETURNING $EntryColumns
      """.query[EntryRow].run().head
      // The audit records the request even when it changed nothing (an `update` whose previous values equal the new ones):
      // who asked, and when, is worth keeping, and the audit is not a version anyone compares.
      audited(if existing.isDefined then AntifraudListAction.update else AntifraudListAction.create, actor, at, stored, existing)
      // The version moves only when what a cell copies does: a repeat of what is stored changes nothing, and a cell that
      // downloaded and rewrote the lists for it would do it for every instance of every cell.
      if existing.forall(_.copiedFieldsDiffer(stored)) then bumped(stored.id, removed = false, at)
      stored.toRecord

  override def delete(id: UUID, actor: String, at: Instant): Task[Boolean] =
    xa.transactMeasured("delete-antifraud-list-entry"):
      val removed = sql"DELETE FROM antifraud_list_entries WHERE id = $id RETURNING $EntryColumns".query[EntryRow].run().headOption
      removed.foreach(row => audited(AntifraudListAction.delete, actor, at, row, previous = None))
      removed.foreach(row => bumped(row.id, removed = true, at))
      removed.isDefined

  override def find(id: UUID): Task[Option[ListEntryRecord]] =
    xa.connectMeasured("find-antifraud-list-entry"):
      sql"SELECT $EntryColumns FROM antifraud_list_entries WHERE id = $id".query[EntryRow].run().headOption.map(_.toRecord)

  override def list(filter: ListEntryFilter, limit: Int, offset: Int): Task[List[ListEntryRecord]] =
    xa.connectMeasured("list-antifraud-list-entries"):
      val listType = filter.listType.map(_.toString)
      val valueType = filter.valueType.map(_.toString)
      val (scoped, tenant) = filter.tenantId match
        case None => (false, Option.empty[String])
        case Some(t) => (true, t.map(identity[String]))
      val (limited, visible) = visibility(filter.visibleTo)
      sql"""
        SELECT $EntryColumns FROM antifraud_list_entries
        WHERE (${listType.isEmpty} OR type = ${listType.getOrElse("")})
          AND (${valueType.isEmpty} OR value_type = ${valueType.getOrElse("")})
          AND (NOT $scoped OR tenant_id IS NOT DISTINCT FROM $tenant)
          AND (NOT $limited OR tenant_id IS NULL OR tenant_id = $visible)
        ORDER BY created_at DESC, id
        LIMIT $limit OFFSET $offset
      """.query[EntryRow].run().map(_.toRecord).toList

  override def active(now: Instant): Task[PolicyLists] = lists(since = None, now)

  override def lists(since: Option[Long], now: Instant): Task[PolicyLists] =
    xa.transactMeasured("load-antifraud-lists"):
      // One snapshot for the version, the changes and the rows: a version read apart from them could be that of other rows,
      // and the cell that stored them under it would not read the lists again.
      sql"SET TRANSACTION ISOLATION LEVEL REPEATABLE READ".update.run()
      val version = currentVersion()
      def whole =
        val entries = sql"""
          SELECT $EntryColumns FROM antifraud_list_entries WHERE expires_at IS NULL OR expires_at > $now
        """.query[EntryRow].run().map(_.toRecord).toList
        PolicyLists(version = version, full = true, entries = entries, removed = Nil)
      since match
        case Some(from) if from == version => PolicyLists(version = version, full = false, entries = Nil, removed = Nil)
        case Some(from) if from < version =>
          // A delta only when there is a change for every version after `from`: one pruned, or never written, makes the
          // delta miss an entry, and the cell would keep a copy that is wrong with nothing to say so.
          val kept = sql"SELECT count(*) FROM antifraud_list_changes WHERE version > $from AND version <= $version".query[Long].run().head
          if kept != version - from then whole
          else
            val entries = sql"""
              SELECT $EntryColumns FROM antifraud_list_entries
              WHERE (expires_at IS NULL OR expires_at > $now)
                AND id IN (SELECT entry_id FROM antifraud_list_changes WHERE version > $from AND version <= $version)
            """.query[EntryRow].run().map(_.toRecord).toList
            val removed = sql"""
              SELECT DISTINCT c.entry_id FROM antifraud_list_changes c
              WHERE c.version > $from AND c.version <= $version
                AND NOT EXISTS (
                  SELECT 1 FROM antifraud_list_entries e WHERE e.id = c.entry_id AND (e.expires_at IS NULL OR e.expires_at > $now)
                )
            """.query[UUID].run().toList
            PolicyLists(version = version, full = false, entries = entries, removed = removed)
        case _ => whole

  override def pruneChanges(before: Instant, limit: Int): Task[Int] =
    xa.connectMeasured("prune-antifraud-list-changes"):
      sql"""
        WITH due AS MATERIALIZED (
          SELECT version FROM antifraud_list_changes WHERE at < $before ORDER BY version LIMIT $limit FOR UPDATE SKIP LOCKED
        )
        DELETE FROM antifraud_list_changes c USING due WHERE c.version = due.version
      """.update.run()

  override def purgeExpired(now: Instant, limit: Int): Task[Int] =
    xa.transactMeasured("purge-antifraud-list-entries"):
      val removed = sql"""
        WITH due AS MATERIALIZED (
          SELECT id FROM antifraud_list_entries WHERE expires_at <= $now ORDER BY expires_at LIMIT $limit FOR UPDATE SKIP LOCKED
        )
        DELETE FROM antifraud_list_entries e USING due WHERE e.id = due.id RETURNING $EntryReturning
      """.query[EntryRow].run()
      removed.foreach(row => audited(AntifraudListAction.expire, "system", now, row, previous = None))
      // No bump: `active` leaves these entries out already, and a cell filters by its own clock, so what a cell copies does not
      // change when they are removed. Bumping made every instance of every cell read the lists again every 30 seconds.
      removed.size

  override def pruneAudit(before: Instant, limit: Int): Task[Int] =
    xa.connectMeasured("prune-antifraud-list-audit"):
      sql"""
        WITH due AS MATERIALIZED (
          SELECT id FROM antifraud_list_audit WHERE at < $before ORDER BY at LIMIT $limit FOR UPDATE SKIP LOCKED
        )
        DELETE FROM antifraud_list_audit a USING due WHERE a.id = due.id
      """.update.run()

  // Two statements, not one with `$x IS NULL OR entry_id = $x`: a cached generic plan would have to serve both.
  override def audit(entryId: Option[UUID], limit: Int, visibleTo: Option[TenantId]): Task[List[AuditRecordResponse]] =
    xa.connectMeasured("list-antifraud-list-audit"):
      val (limited, visible) = visibility(visibleTo)
      entryId match
        case Some(id) =>
          sql"""
            SELECT $AuditColumns
            FROM antifraud_list_audit WHERE entry_id = $id AND (NOT $limited OR tenant_id IS NULL OR tenant_id = $visible)
            ORDER BY at DESC, id LIMIT $limit
          """.query[AuditRow].run().map(_.toAudit).toList
        case None =>
          sql"""
            SELECT $AuditColumns
            FROM antifraud_list_audit WHERE (NOT $limited OR tenant_id IS NULL OR tenant_id = $visible)
            ORDER BY at DESC, id LIMIT $limit
          """.query[AuditRow].run().map(_.toAudit).toList

  /** A parameter that is never null: whether to limit to the global entries and one tenant's, and that tenant. */
  private def visibility(visibleTo: Option[TenantId]): (Boolean, String) =
    (visibleTo.isDefined, visibleTo.fold("")(identity[String]))

  // Read with the instant codec in scope, so the rows are defined here and not in the companion.
  private case class EntryRow(
      id: UUID,
      listType: String,
      valueType: String,
      value: String,
      tenantId: Option[String],
      expiresAt: Option[Instant],
      reason: Option[String],
      source: String,
      shadow: Boolean,
      createdBy: String,
      createdAt: Instant,
  ) derives DbCodec:
    /** Whether `other`, the same entry as stored now, differs in a field a cell copies. */
    def copiedFieldsDiffer(other: EntryRow): Boolean =
      expiresAt != other.expiresAt || reason != other.reason || source != other.source || shadow != other.shadow

    def toRecord: ListEntryRecord = ListEntryRecord(
      id = id,
      listType = AntifraudListType.valueOf(listType),
      valueType = AntifraudValueType.valueOf(valueType),
      value = value,
      tenantId = tenantId.map(TenantId(_)),
      expiresAt = expiresAt,
      reason = reason,
      source = source,
      shadow = shadow,
      createdBy = createdBy,
      createdAt = createdAt,
    )

  private case class AuditRow(
      id: UUID,
      at: Instant,
      action: String,
      actor: String,
      entryId: UUID,
      listType: String,
      valueType: String,
      value: String,
      tenantId: Option[String],
      expiresAt: Option[Instant],
      reason: Option[String],
      previousExpiresAt: Option[Instant],
      previousReason: Option[String],
      previousSource: Option[String],
      previousShadow: Option[Boolean],
  ) derives DbCodec:
    def toAudit: AuditRecordResponse = AuditRecordResponse(
      id = id,
      at = at,
      action = AntifraudListAction.valueOf(action),
      actor = actor,
      entryId = entryId,
      listType = AntifraudListType.valueOf(listType),
      valueType = AntifraudValueType.valueOf(valueType),
      value = value,
      tenantId = tenantId.map(TenantId(_)),
      expiresAt = expiresAt,
      reason = reason,
      // Only a record of an update has them.
      previous =
        for
          source <- previousSource
          shadow <- previousShadow
        yield PreviousListValuesResponse(expiresAt = previousExpiresAt, reason = previousReason, source = source, shadow = shadow),
    )

object PostgresAntifraudListRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudListRepository] =
    ZLayer.fromFunction(PostgresAntifraudListRepository(_))

  private val EntryReturning = SqlLiteral(
    "e.id, e.type, e.value_type, e.value, e.tenant_id, e.expires_at, e.reason, e.source, e.shadow, e.created_by, e.created_at",
  )

  private val AuditColumns = SqlLiteral(
    "id, at, action, actor, entry_id, type, value_type, value, tenant_id, expires_at, reason, previous_expires_at, previous_reason, previous_source, previous_shadow",
  )

  private val EntryColumns = SqlLiteral(
    "id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at",
  )
