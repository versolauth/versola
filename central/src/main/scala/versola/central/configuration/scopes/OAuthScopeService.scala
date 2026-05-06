package versola.central.configuration.scopes

import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateScopeRequest, UpdateScopeRequest}
import versola.util.ReloadingCache
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, ZLayer, durationInt}

trait OAuthScopeService:
  def getAllScopes: Task[Vector[ScopeRecord]]

  def getTenantScopes(
      tenantId: TenantId,
      offset: Int = 0,
      limit: Option[Int] = None,
  ): Task[Vector[ScopeRecord]]

  def createScope(
      request: CreateScopeRequest,
  ): Task[Unit]

  def updateScope(
      request: UpdateScopeRequest,
  ): Task[Unit]

  def deleteScope(
      tenantId: TenantId,
      scopeId: ScopeToken,
  ): Task[Unit]

  def sync(
      event: SyncEvent.ScopesUpdated,
  ): Task[Unit]

object OAuthScopeService:
  def live(
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZLayer[OAuthScopeRepository & Scope, Throwable, OAuthScopeService] =
    ZLayer(ReloadingCache.make[Vector[ScopeRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[ScopeRecord]],
      scopeRepository: OAuthScopeRepository,
  ) extends OAuthScopeService:

    override def getAllScopes: Task[Vector[ScopeRecord]] =
      cache.get

    override def getTenantScopes(
        tenantId: TenantId,
        offset: Int,
        limit: Option[Int],
    ): Task[Vector[ScopeRecord]] =
      cache.get.map { records =>
        records.filter(_.tenantId == tenantId)
          .slice(offset, limit.fold(records.size)(offset + _))
      }

    override def createScope(
        request: CreateScopeRequest,
    ): Task[Unit] =
      scopeRepository.createScope(request.tenantId, request.id, request.description, request.claims)

    override def updateScope(
        request: UpdateScopeRequest,
    ): Task[Unit] =
      scopeRepository.updateScope(request.tenantId, request.id, request.patch)

    override def deleteScope(
        tenantId: TenantId,
        scopeId: ScopeToken,
    ): Task[Unit] =
      scopeRepository.deleteScope(tenantId, scopeId)

    override def sync(
        event: SyncEvent.ScopesUpdated,
    ): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        scopeRepository.findScope(event.tenantId, event.id),
      )
