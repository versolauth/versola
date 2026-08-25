package versola.central.configuration.tenants

import versola.central.CentralConfig
import versola.central.configuration.{CreateTenantRequest, UpdateTenantRequest}
import versola.central.configuration.edges.EdgeId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer, durationInt}

trait TenantService:
  def getAllTenants: Task[Vector[TenantRecord]]

  def createTenant(
      request: CreateTenantRequest,
  ): Task[Unit]

  def updateTenant(
      request: UpdateTenantRequest,
  ): Task[Unit]

  def deleteTenant(
      id: TenantId,
  ): Task[Unit]

  def sync(): Task[Unit]

object TenantService:
  def live: ZLayer[TenantRepository & Scope & CentralConfig, Throwable, TenantService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[TenantRecord]](config.configurationCacheRefreshInterval),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      cache: ReloadingCache[Vector[TenantRecord]],
      tenantRepository: TenantRepository,
  ) extends TenantService:
    export tenantRepository.deleteTenant

    def getAllTenants: Task[Vector[TenantRecord]] =
      cache.get.map(_.sortBy(_.id))

    override def createTenant(
        request: CreateTenantRequest,
    ): Task[Unit] =
      tenantRepository.createTenant(request.id, request.description, request.edgeId.map(EdgeId(_)))

    override def updateTenant(
        request: UpdateTenantRequest,
    ): Task[Unit] =
      tenantRepository.updateTenant(request.id, request.description, request.edgeId.map(EdgeId(_)))

    override def sync(): Task[Unit] =
      for
        tenants <- tenantRepository.getAll
        _ <- cache.set(tenants)
      yield ()
