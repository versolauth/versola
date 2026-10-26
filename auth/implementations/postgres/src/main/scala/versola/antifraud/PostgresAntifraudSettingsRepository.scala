package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Task, ZIO, ZLayer}

import java.time.Instant
import java.util.UUID

/** The global row has the tenant id `''`, which no tenant has. */
class PostgresAntifraudSettingsRepository(xa: TransactorZIO) extends AntifraudSettingsRepository, BasicCodecs:
  import PostgresAntifraudSettingsRepository.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  private def scope(tenant: Option[TenantId]): String = tenant.map(identity[String]).getOrElse("")

  private def tenantOf(scope: String): Option[TenantId] = Option.when(scope.nonEmpty)(TenantId(scope))

  private def audited(scope: String, actor: String, at: Instant, before: Option[String], after: Option[String])(using DbCon): Unit =
    sql"""
      INSERT INTO af_settings_audit (id, at, actor, tenant_id, before, after)
      VALUES (${UUID.randomUUID()}, $at, $actor, $scope, $before, $after)
    """.update.run()
    ()

  private def bumped()(using DbCon): Unit =
    sql"UPDATE af_settings_version SET version = version + 1 WHERE id = 1".update.run()
    ()

  override def put(tenant: Option[TenantId], patch: TuningPatch, actor: String, at: Instant): Task[Unit] =
    val key = scope(tenant)
    val json = patch.toJson
    xa.transactMeasured("put-antifraud-settings"):
      val before = sql"SELECT settings FROM af_settings WHERE tenant_id = $key FOR UPDATE".query[String].run().headOption
      sql"""
        INSERT INTO af_settings (tenant_id, settings, updated_by, updated_at) VALUES ($key, $json, $actor, $at)
        ON CONFLICT (tenant_id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
      """.update.run()
      audited(key, actor, at, before, Some(json))
      bumped()

  override def delete(tenant: Option[TenantId], actor: String, at: Instant): Task[Boolean] =
    val key = scope(tenant)
    xa.transactMeasured("delete-antifraud-settings"):
      val removed = sql"DELETE FROM af_settings WHERE tenant_id = $key RETURNING settings".query[String].run().headOption
      removed.foreach: before =>
        audited(key, actor, at, Some(before), None)
        bumped()
      removed.isDefined

  override def find(tenant: Option[TenantId]): Task[Option[TuningPatch]] =
    val key = scope(tenant)
    xa.connectMeasured("find-antifraud-settings"):
      sql"SELECT settings FROM af_settings WHERE tenant_id = $key".query[String].run().headOption
    .flatMap(ZIO.foreach(_)(decode))

  override def version: Task[Long] =
    xa.connectMeasured("antifraud-settings-version"):
      sql"SELECT version FROM af_settings_version WHERE id = 1".query[Long].run().head

  override def loadAll: Task[List[(Option[TenantId], TuningPatch)]] =
    xa.connectMeasured("load-antifraud-settings"):
      sql"SELECT tenant_id, settings FROM af_settings".query[Stored].run().toList
    .flatMap(rows => ZIO.foreach(rows)(row => decode(row.settings).map(tenantOf(row.tenantId) -> _)))

  // Two statements, not one with `$x IS NULL OR tenant_id = $x`: a cached generic plan would have to serve both.
  override def audit(scope: Option[Option[TenantId]], limit: Int): Task[List[AntifraudTuningAudit]] =
    xa.connectMeasured("list-antifraud-settings-audit"):
      scope match
        case Some(tenant) =>
          val key = this.scope(tenant)
          sql"SELECT at, actor, tenant_id, before, after FROM af_settings_audit WHERE tenant_id = $key ORDER BY at DESC, id LIMIT $limit"
            .query[AuditRow].run().toList
        case None =>
          sql"SELECT at, actor, tenant_id, before, after FROM af_settings_audit ORDER BY at DESC, id LIMIT $limit"
            .query[AuditRow].run().toList
    .flatMap: rows =>
      ZIO.foreach(rows): row =>
        for
          before <- ZIO.foreach(row.before)(decode)
          after <- ZIO.foreach(row.after)(decode)
        yield AntifraudTuningAudit(row.at, row.actor, tenantOf(row.tenantId), before, after)

  private def decode(json: String): Task[TuningPatch] =
    ZIO.fromEither(json.fromJson[TuningPatch]).mapError(message => RuntimeException(s"stored antifraud settings are not valid: $message"))

  private case class Stored(tenantId: String, settings: String) derives DbCodec

  private case class AuditRow(at: Instant, actor: String, tenantId: String, before: Option[String], after: Option[String]) derives DbCodec

object PostgresAntifraudSettingsRepository:
  val live: ZLayer[TransactorZIO, Nothing, AntifraudSettingsRepository] =
    ZLayer.fromFunction(PostgresAntifraudSettingsRepository(_))
