package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresAntifraudListRepository(xa: TransactorZIO) extends AntifraudListRepository, BasicCodecs:
  import PostgresAntifraudListRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private def audited(action: AntifraudListAction, actor: String, at: Instant, entry: EntryRow)(using DbCon): Unit =
    sql"""
      INSERT INTO af_list_audit (id, at, action, actor, entry_id, type, value_type, value, tenant_id, expires_at, reason)
      VALUES (${UUID.randomUUID()}, $at, ${action.toString}, $actor, ${entry.id}, ${entry.listType}, ${entry.valueType},
              ${entry.value}, ${entry.tenantId}, ${entry.expiresAt}, ${entry.reason})
    """.update.run()
    ()

  private def bumped()(using DbCon): Unit =
    sql"UPDATE af_list_version SET version = version + 1 WHERE id = 1".update.run()
    ()

  override def version: Task[Long] =
    xa.connectMeasured("antifraud-list-version"):
      sql"SELECT version FROM af_list_version WHERE id = 1".query[Long].run().head

  override def upsert(entry: AntifraudListEntry, actor: String, at: Instant): Task[AntifraudListEntry] =
    xa.transactMeasured("upsert-antifraud-list-entry"):
      val stored = sql"""
        INSERT INTO af_list_entry (id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at)
        VALUES (${entry.id}, ${entry.listType.toString}, ${entry.valueType.toString}, ${entry.value},
                ${entry.tenantId.map(identity[String])}, ${entry.expiresAt}, ${entry.reason}, ${entry.source},
                ${entry.shadow}, ${entry.createdBy}, ${entry.createdAt})
        ON CONFLICT (type, value_type, value, COALESCE(tenant_id, '')) DO UPDATE
          SET expires_at = EXCLUDED.expires_at, reason = EXCLUDED.reason, source = EXCLUDED.source,
              shadow = EXCLUDED.shadow
        RETURNING $EntryColumns
      """.query[EntryRow].run().head
      audited(AntifraudListAction.create, actor, at, stored)
      bumped()
      stored.toEntry

  override def delete(id: UUID, actor: String, at: Instant): Task[Boolean] =
    xa.transactMeasured("delete-antifraud-list-entry"):
      val removed = sql"DELETE FROM af_list_entry WHERE id = $id RETURNING $EntryColumns".query[EntryRow].run().headOption
      removed.foreach(row => audited(AntifraudListAction.delete, actor, at, row))
      if removed.isDefined then bumped()
      removed.isDefined

  override def find(id: UUID): Task[Option[AntifraudListEntry]] =
    xa.connectMeasured("find-antifraud-list-entry"):
      sql"SELECT $EntryColumns FROM af_list_entry WHERE id = $id".query[EntryRow].run().headOption.map(_.toEntry)

  override def preview(valueType: AntifraudValueType, value: String, since: Instant): Task[ListPreview] =
    xa.connectMeasured("preview-antifraud-list-entry"):
      // Only the columns the event log has can say what an entry would have matched.
      val counts = valueType match
        case AntifraudValueType.phone_hash =>
          Some(sql"SELECT COUNT(*) FILTER (WHERE subject_hash = $value), COUNT(*) FROM af_event WHERE at >= $since".query[Counts].run().head)
        case AntifraudValueType.prefix =>
          Some(
            sql"SELECT COUNT(*) FILTER (WHERE left(dest_prefix, ${value.length}) = $value), COUNT(*) FROM af_event WHERE at >= $since"
              .query[Counts].run().head,
          )
        case AntifraudValueType.ua_hash =>
          Some(
            sql"SELECT COUNT(*) FILTER (WHERE encode(sha256(convert_to(user_agent, 'UTF8')), 'hex') = $value), COUNT(*) FROM af_event WHERE at >= $since"
              .query[Counts].run().head,
          )
        case _ => None
      counts match
        case Some(c) => ListPreview(matched = Some(c.matched), total = c.total)
        case None => ListPreview(matched = None, total = sql"SELECT COUNT(*) FROM af_event WHERE at >= $since".query[Long].run().head)

  override def list(filter: AntifraudListFilter, limit: Int, offset: Int): Task[List[AntifraudListEntry]] =
    xa.connectMeasured("list-antifraud-list-entries"):
      val listType = filter.listType.map(_.toString)
      val valueType = filter.valueType.map(_.toString)
      val (scoped, tenant) = filter.tenantId match
        case None => (false, Option.empty[String])
        case Some(t) => (true, t.map(identity[String]))
      sql"""
        SELECT $EntryColumns FROM af_list_entry
        WHERE (${listType.isEmpty} OR type = ${listType.getOrElse("")})
          AND (${valueType.isEmpty} OR value_type = ${valueType.getOrElse("")})
          AND (NOT $scoped OR tenant_id IS NOT DISTINCT FROM $tenant)
        ORDER BY created_at DESC, id
        LIMIT $limit OFFSET $offset
      """.query[EntryRow].run().map(_.toEntry).toList

  override def loadActive(now: Instant): Task[List[ListedValue]] =
    xa.connectMeasured("load-antifraud-list-entries"):
      sql"""
        SELECT id, type, value_type, value, tenant_id, expires_at, shadow
        FROM af_list_entry WHERE expires_at IS NULL OR expires_at > $now
      """.query[ListedRow].run().iterator.flatMap(_.toListed).toList

  override def addHits(hits: List[ListHit]): Task[Unit] =
    if hits.isEmpty then zio.ZIO.unit
    else
      xa.transactMeasured("add-antifraud-list-hits"):
        batchUpdate(hits): hit =>
          sql"""
            UPDATE af_list_entry
            SET hit_count = hit_count + ${hit.count}, last_hit_at = GREATEST(COALESCE(last_hit_at, ${hit.lastAt}), ${hit.lastAt})
            WHERE id = ${hit.id}
          """.update
      .unit

  override def purgeExpired(now: Instant, limit: Int): Task[Int] =
    xa.transactMeasured("purge-antifraud-list-entries"):
      val removed = sql"""
        WITH due AS MATERIALIZED (
          SELECT id FROM af_list_entry WHERE expires_at <= $now ORDER BY expires_at LIMIT $limit FOR UPDATE SKIP LOCKED
        )
        DELETE FROM af_list_entry e USING due WHERE e.id = due.id RETURNING $EntryReturning
      """.query[EntryRow].run()
      removed.foreach(row => audited(AntifraudListAction.expire, "system", now, row))
      if removed.nonEmpty then bumped()
      removed.size

  override def pruneAudit(before: Instant, limit: Int): Task[Int] =
    xa.connectMeasured("prune-antifraud-list-audit"):
      sql"""
        WITH due AS MATERIALIZED (
          SELECT id FROM af_list_audit WHERE at < $before ORDER BY at LIMIT $limit FOR UPDATE SKIP LOCKED
        )
        DELETE FROM af_list_audit a USING due WHERE a.id = due.id
      """.update.run()

  // Two statements, not one with `$x IS NULL OR entry_id = $x`: a cached generic plan would have to serve both.
  override def audit(entryId: Option[UUID], limit: Int): Task[List[AntifraudListAudit]] =
    xa.connectMeasured("list-antifraud-list-audit"):
      entryId match
        case Some(id) =>
          sql"""
            SELECT id, at, action, actor, entry_id, type, value_type, value, tenant_id, expires_at, reason
            FROM af_list_audit WHERE entry_id = $id ORDER BY at DESC, id LIMIT $limit
          """.query[AuditRow].run().map(_.toAudit).toList
        case None =>
          sql"""
            SELECT id, at, action, actor, entry_id, type, value_type, value, tenant_id, expires_at, reason
            FROM af_list_audit ORDER BY at DESC, id LIMIT $limit
          """.query[AuditRow].run().map(_.toAudit).toList

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
      hitCount: Long,
      lastHitAt: Option[Instant],
  ) derives DbCodec:
    def toEntry: AntifraudListEntry = AntifraudListEntry(
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
      hitCount = hitCount,
      lastHitAt = lastHitAt,
    )

  private case class Counts(matched: Long, total: Long) derives DbCodec

  private case class ListedRow(
      id: UUID,
      listType: String,
      valueType: String,
      value: String,
      tenantId: Option[String],
      expiresAt: Option[Instant],
      shadow: Boolean,
  ) derives DbCodec:
    /** A row whose type this version does not know is skipped, not fatal: the table is read for every refresh. */
    def toListed: Option[ListedValue] =
      for
        lt <- AntifraudListType.values.find(_.toString == listType)
        vt <- AntifraudValueType.values.find(_.toString == valueType)
      yield ListedValue(
        id = id,
        listType = lt,
        valueType = vt,
        value = value,
        tenantId = tenantId.map(TenantId(_)),
        expiresAt = expiresAt,
        shadow = shadow,
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
  ) derives DbCodec:
    def toAudit: AntifraudListAudit = AntifraudListAudit(
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
    )

object PostgresAntifraudListRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudListRepository] =
    ZLayer.fromFunction(PostgresAntifraudListRepository(_))

  private val EntryReturning = SqlLiteral(
    "e.id, e.type, e.value_type, e.value, e.tenant_id, e.expires_at, e.reason, e.source, e.shadow, e.created_by, e.created_at, e.hit_count, e.last_hit_at",
  )

  private val EntryColumns = SqlLiteral(
    "id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at, hit_count, last_hit_at",
  )
