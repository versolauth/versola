package versola.central.configuration.clients

import versola.central.configuration.SaveAuthorizationPresetsRequest
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer, durationInt}

trait AuthorizationPresetService:

  def getClientPresets(clientId: ClientId): Task[Vector[AuthorizationPreset]]

  def savePresets(request: SaveAuthorizationPresetsRequest): Task[Either[PresetValidationError, Unit]]

  def getPresetsForSync(tenantIds: Option[Set[TenantId]]): Task[Vector[AuthorizationPreset]]

  def sync(event: SyncEvent.PresetsUpdated): Task[Unit]

object AuthorizationPresetService:
  def live(
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZLayer[AuthorizationPresetRepository & OAuthClientRepository & Scope, Throwable, AuthorizationPresetService] =
    ZLayer(ReloadingCache.make[Vector[AuthorizationPreset]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      cache: ReloadingCache[Vector[AuthorizationPreset]],
      repository: AuthorizationPresetRepository,
      clientRepository: OAuthClientRepository,
  ) extends AuthorizationPresetService:

    override def getClientPresets(clientId: ClientId): Task[Vector[AuthorizationPreset]] =
      cache.get.map(_.filter(p => p.clientId == clientId))

    override def savePresets(request: SaveAuthorizationPresetsRequest): Task[Either[PresetValidationError, Unit]] =
      (for
        client <- clientRepository.find(request.clientId)
          .someOrFail(PresetValidationError.ClientNotFound)

        _ <- ZIO.foreachDiscard(request.presets) {
          presetRequest =>
            for
              _ <- ZIO.when(!client.redirectUris.contains(presetRequest.redirectUri))(
                ZIO.fail(PresetValidationError.InvalidRedirectUri),
              )
              _ <- ZIO.when(!presetRequest.scope.subsetOf(client.scope))(
                ZIO.fail(PresetValidationError.InvalidScope),
              )
            yield ()
        }

        presets = request.presets.map: presetRequest =>
          AuthorizationPreset(
            id = presetRequest.id,
            clientId = request.clientId,
            description = presetRequest.description,
            redirectUri = presetRequest.redirectUri,
            scope = presetRequest.scope,
            responseType = presetRequest.responseType,
            uiLocales = presetRequest.uiLocales,
            customParameters = presetRequest.customParameters,
          )

        _ <- repository.replace(request.clientId, presets)
      yield ())
        .either
        .flatMap {
          case Left(ex: Throwable) => ZIO.fail(ex)
          case Left(error: PresetValidationError) => ZIO.left(error)
          case Right(_) => ZIO.right(())
        }

    override def getPresetsForSync(tenantIds: Option[Set[TenantId]]): Task[Vector[AuthorizationPreset]] =
      tenantIds match
        case None => cache.get
        case Some(ids) =>
          for
            presets <- cache.get
            clientsCache <- clientRepository.getAll
            allowedClientIds = clientsCache.filter(c => ids.contains(c.tenantId)).map(_.id).toSet
          yield presets.filter(p => allowedClientIds.contains(p.clientId))

    override def sync(event: SyncEvent.PresetsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.find(event.id),
      )

sealed trait PresetValidationError

object PresetValidationError:
  case object ClientNotFound extends PresetValidationError
  case object InvalidRedirectUri extends PresetValidationError
  case object InvalidScope extends PresetValidationError
