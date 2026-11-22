package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.util.postgres.BasicCodecs
import zio.{Duration, Task, ZLayer, durationInt}

import java.time.Instant
import java.util.UUID

/** @param previewTimeout   the longest a preview (a day of `af_event` read in full) may take before the creation fails
  * @param statementTimeout the longest any other statement may take: the refresh, the hit flush and the clean-up run on
  *                         fibers of their own, and one that hangs on a lock or a slow disk would stop for good without
  *                         moving `*_refresh_failed_total`
  */
class PostgresAntifraudListRepository(xa: TransactorZIO, previewTimeout: Duration = 10.seconds, statementTimeout: Duration = 10.seconds)
  extends AntifraudListRepository,
    BasicCodecs:
  import PostgresAntifraudEventRepository.CountedRequest
  import PostgresAntifraudListRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[ListHit] = DbCodec.derived

  /** `SET LOCAL` holds for the transaction only, so every method runs in one. */
  private def timed(limit: Duration = statementTimeout)(using DbCon): Unit =
    sql"SET LOCAL statement_timeout = ${SqlLiteral(s"'${limit.toMillis}ms'")}".update.run()

  override def version: Task[Long] =
    xa.transactMeasured("antifraud-list-version"):
      timed()
      sql"SELECT version FROM af_list_version WHERE id = 1".query[Long].run().head

  override def replaceAll(entries: List[PolicyListEntry], version: Long): Task[Boolean] =
    xa.transactMeasured("replace-antifraud-list-entries"):
      timed()
      // The row of the version is the lock: of several instances that read the same change one writes it and the others
      // find the copy at that version.
      val current = sql"SELECT version FROM af_list_version WHERE id = 1 FOR UPDATE".query[Long].run().head
      if current == version then false
      else
        val ids = entries.map(_.id).toArray
        sql"DELETE FROM af_list_entry WHERE NOT (id = ANY($ids))".update.run()
        if entries.nonEmpty then
          batchUpdate(entries): entry =>
            // An entry that is there keeps its hit counters, which are this cell's. Central refreshes an entry under the id
            // it has, so the id is the key; the value of a row that was written here by hand under another id gives way.
            sql"""
              INSERT INTO af_list_entry (id, type, value_type, value, tenant_id, expires_at, reason, source, shadow, created_by, created_at)
              VALUES (${entry.id}, ${entry.listType}, ${entry.valueType}, ${entry.value}, ${entry.tenantId}, ${entry.expiresAt},
                      ${entry.reason}, ${entry.source}, ${entry.shadow}, ${entry.createdBy}, ${entry.createdAt})
              ON CONFLICT (id) DO UPDATE
                SET type = EXCLUDED.type, value_type = EXCLUDED.value_type, value = EXCLUDED.value, tenant_id = EXCLUDED.tenant_id,
                    expires_at = EXCLUDED.expires_at, reason = EXCLUDED.reason, source = EXCLUDED.source, shadow = EXCLUDED.shadow
            """.update
        sql"UPDATE af_list_version SET version = $version WHERE id = 1".update.run()
        true

  override def preview(valueType: AntifraudValueType, value: String, tenantId: Option[TenantId], since: Instant): Task[ListPreview] =
    xa.transactMeasured("preview-antifraud-list-entry"):
      // A day of the log is read in full (the BRIN index on `at` bounds it to that day's blocks); on a log too large
      // for that the creation fails here, on time, rather than hold the connection, and the entry can be added as a
      // shadow one.
      timed(previewTimeout)
      // An entry of a tenant applies to that tenant's events alone, and a global one to all of them: the share is over what
      // the entry could have matched.
      val scoped = tenantId.isDefined
      val tenant = tenantId.map(identity[String]).getOrElse("")
      // Only the columns the event log has can say what an entry would have matched.
      valueType match
        case AntifraudValueType.phone_hash =>
          val c = sql"""
            SELECT COUNT(*) FILTER (WHERE subject_hash = $value), COUNT(*) FROM af_event
            WHERE at >= $since AND (NOT $scoped OR tenant_id = $tenant)
          """.query[Counts].run().head
          ListPreview(matched = Some(c.matched), total = c.total)
        case AntifraudValueType.prefix if value.length > StatsPrefixLength =>
          // The statistics keep only the first `StatsPrefixLength` digits of a number, so they cannot tell a longer prefix from the
          // range it falls in, and the share of the whole range would be held against a narrow entry. The event log keeps more
          // digits per event, and is counted here for the entry alone. The events are the ones the rollup makes `requests` of (the
          // same channel and `CountedRequest`), so that matched / total is the ratio the rollup would give for the same traffic.
          val c = sql"""
            SELECT COUNT(*) FILTER (WHERE starts_with(dest_prefix, $value)), COUNT(*) FROM af_event
            WHERE channel = 'sms' AND dest_prefix IS NOT NULL AND $CountedRequest
              AND at >= $since AND (NOT $scoped OR tenant_id = $tenant)
          """.query[Counts].run().head
          ListPreview(matched = Some(c.matched), total = c.total)
        case AntifraudValueType.prefix =>
          // The rolled-up requests of the range per minute, not the day's events: the traffic of a range is what
          // af_dest_stats is made of, and reading it is a few hundred rows. A value of up to `StatsPrefixLength` digits is a range
          // of the statistics, or one that contains such ranges.
          val c = sql"""
            SELECT COALESCE(SUM(requests) FILTER (WHERE starts_with(dest_prefix, $value) OR starts_with($value, dest_prefix)), 0),
                   COALESCE(SUM(requests), 0)
            FROM af_dest_stats
            WHERE minute >= $since AND (NOT $scoped OR tenant_id = $tenant)
          """.query[Counts].run().head
          ListPreview(matched = Some(c.matched), total = c.total)
        case AntifraudValueType.asn =>
          // The AS number is normalised to digits; compared as a bigint so that a number above Int.MaxValue (no AS in use)
          // matches nothing instead of wrapping.
          val c = sql"""
            SELECT COUNT(*) FILTER (WHERE asn = ${value.toLong}), COUNT(*) FROM af_event
            WHERE at >= $since AND (NOT $scoped OR tenant_id = $tenant)
          """.query[Counts].run().head
          ListPreview(matched = Some(c.matched), total = c.total)
        case AntifraudValueType.country =>
          // Normalised to upper case, as the column is written.
          val c = sql"""
            SELECT COUNT(*) FILTER (WHERE country = $value), COUNT(*) FROM af_event
            WHERE at >= $since AND (NOT $scoped OR tenant_id = $tenant)
          """.query[Counts].run().head
          ListPreview(matched = Some(c.matched), total = c.total)
        case AntifraudValueType.ua_hash =>
          val c = sql"""
            SELECT COUNT(*) FILTER (WHERE encode(sha256(convert_to(user_agent, 'UTF8')), 'hex') = $value), COUNT(*) FROM af_event
            WHERE at >= $since AND (NOT $scoped OR tenant_id = $tenant)
          """.query[Counts].run().head
          ListPreview(matched = Some(c.matched), total = c.total)
        case _ =>
          val total = sql"SELECT COUNT(*) FROM af_event WHERE at >= $since AND (NOT $scoped OR tenant_id = $tenant)".query[Long].run().head
          ListPreview(matched = None, total = total)

  override def loadActive(now: Instant): Task[List[ListedValue]] =
    xa.transactMeasured("load-antifraud-list-entries"):
      timed()
      sql"""
        SELECT id, type, value_type, value, tenant_id, expires_at, shadow
        FROM af_list_entry WHERE expires_at IS NULL OR expires_at > $now
      """.query[ListedRow].run().iterator.flatMap(_.toListed).toList

  override def addHits(hits: List[ListHit]): Task[Unit] =
    if hits.isEmpty then zio.ZIO.unit
    else
      xa.transactMeasured("add-antifraud-list-hits"):
        timed()
        batchUpdate(hits): hit =>
          sql"""
            UPDATE af_list_entry
            SET hit_count = hit_count + ${hit.count}, last_hit_at = GREATEST(COALESCE(last_hit_at, ${hit.lastAt}), ${hit.lastAt})
            WHERE id = ${hit.id}
          """.update
      .unit

  override def hits(ids: List[UUID]): Task[List[ListHit]] =
    if ids.isEmpty then zio.ZIO.succeed(Nil)
    else
      xa.transactMeasured("antifraud-list-hits"):
        timed()
        sql"""
          SELECT id, hit_count, last_hit_at FROM af_list_entry
          WHERE id = ANY(${ids.toArray}) AND hit_count > 0 AND last_hit_at IS NOT NULL
        """.query[ListHit].run().toList

  // The newest row stays whatever its age: the next generation is counted from it, and one that restarted at 1 would be
  // below what the instances have applied.
  override def pruneCounterResets(before: Instant, limit: Int): Task[Int] =
    xa.transactMeasured("prune-antifraud-counter-resets"):
      timed()
      sql"""
        DELETE FROM af_counter_reset
        WHERE generation IN (
          SELECT generation FROM af_counter_reset
          WHERE at < $before AND generation < (SELECT MAX(generation) FROM af_counter_reset)
          ORDER BY generation LIMIT $limit
        )
      """.update.run()

  // Read with the instant codec in scope, so the rows are defined here and not in the companion.
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

object PostgresAntifraudListRepository:
  /** The digits of a number a range of `af_dest_stats` has: the rollup is run with this length. */
  private val StatsPrefixLength = AntifraudEvents.Sizing.standard.statsPrefixLength

  val live: ZLayer[TransactorZIO, Nothing, AntifraudListRepository] =
    ZLayer.fromFunction(PostgresAntifraudListRepository(_))
