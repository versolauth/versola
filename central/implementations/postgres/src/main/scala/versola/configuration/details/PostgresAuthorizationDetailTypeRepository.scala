package versola.configuration.details

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.details.{AuthorizationDetailType, AuthorizationDetailTypeRecord, AuthorizationDetailTypeRepository}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Task, ZLayer}

class PostgresAuthorizationDetailTypeRepository(
    xa: TransactorZIO,
) extends AuthorizationDetailTypeRepository, BasicCodecs:
  given DbCodec[AuthorizationDetailType] = DbCodec.StringCodec.biMap(AuthorizationDetailType(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[Json.Obj] = jsonBCodec[Json.Obj]
  given DbCodec[AuthorizationDetailTypeRecord] = DbCodec.derived

  override def getAll: Task[Vector[AuthorizationDetailTypeRecord]] =
    xa.connectMeasured("get-all-authorization-detail-types"):
      sql"""
            SELECT tenant_id, type, description, schema FROM authorization_detail_types
         """.query[AuthorizationDetailTypeRecord].run()

  override def findType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
  ): Task[Option[AuthorizationDetailTypeRecord]] =
    xa.connectMeasured("find-authorization-detail-type"):
      sql"""
            SELECT tenant_id, type, description, schema FROM authorization_detail_types
            WHERE tenant_id = $tenantId AND type = ${`type`}
         """.query[AuthorizationDetailTypeRecord].run().headOption

  override def createType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
      description: Map[String, String],
      schema: Json.Obj,
  ): Task[Unit] =
    xa.connectMeasured("create-authorization-detail-type"):
      sql"""
            INSERT INTO authorization_detail_types (tenant_id, type, description, schema)
            VALUES ($tenantId, ${`type`}, $description, $schema)
         """.update.run()
    .unit

  override def updateType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
      description: Map[String, String],
      schema: Json.Obj,
  ): Task[Unit] =
    xa.connectMeasured("update-authorization-detail-type"):
      sql"""
            UPDATE authorization_detail_types
              SET description = $description,
                  schema = $schema
              WHERE tenant_id = $tenantId AND type = ${`type`}
         """.update.run()
    .unit

  override def deleteType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
  ): Task[Unit] =
    xa.connectMeasured("delete-authorization-detail-type"):
      sql"""DELETE FROM authorization_detail_types WHERE tenant_id = $tenantId AND type = ${`type`}""".update.run()
    .unit

object PostgresAuthorizationDetailTypeRepository:
  def live: ZLayer[TransactorZIO, Nothing, AuthorizationDetailTypeRepository] =
    ZLayer.fromFunction(PostgresAuthorizationDetailTypeRepository(_))
