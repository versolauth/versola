package versola.central.configuration.tenants

import versola.central.configuration.{CreateTenantRequest, UpdateTenantRequest}
import versola.central.configuration.edges.EdgeId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZLayer, durationInt}

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
  def live(
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZLayer[TenantRepository & Scope, Throwable, TenantService] =
    ZLayer(ReloadingCache.make[Vector[TenantRecord]](schedule))
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
