package versola.central.configuration.scopes

import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateClaim, PatchScope}
import versola.util.CacheSource
import zio.Task
import zio.json.ast.Json

trait OAuthScopeRepository extends CacheSource[Vector[ScopeRecord]]:

  def getAll: Task[Vector[ScopeRecord]]

  def findScope(
      tenantId: TenantId,
      scopeId: ScopeToken,
  ): Task[Option[ScopeRecord]]

  def createScope(
      tenantId: TenantId,
      id: ScopeToken,
      description: Map[String, String],
      claims: List[CreateClaim],
  ): Task[Unit]

  def updateScope(
      tenantId: TenantId,
      id: ScopeToken,
      patch: PatchScope,
  ): Task[Unit]

  def deleteScope(
      tenantId: TenantId,
      id: ScopeToken,
  ): Task[Unit]
