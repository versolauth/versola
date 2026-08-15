package versola.central.configuration.details

import versola.central.configuration.tenants.TenantId
import versola.util.CacheSource
import zio.Task
import zio.json.ast.Json

trait AuthorizationDetailTypeRepository extends CacheSource[Vector[AuthorizationDetailTypeRecord]]:

  def getAll: Task[Vector[AuthorizationDetailTypeRecord]]

  def findType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
  ): Task[Option[AuthorizationDetailTypeRecord]]

  def createType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
      description: Map[String, String],
      schema: Json.Obj,
  ): Task[Unit]

  def updateType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
      description: Map[String, String],
      schema: Json.Obj,
  ): Task[Unit]

  def deleteType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
  ): Task[Unit]
