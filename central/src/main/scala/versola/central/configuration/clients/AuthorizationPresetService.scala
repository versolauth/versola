package versola.central.configuration.clients

import versola.central.configuration.SaveAuthorizationPresetsRequest
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

trait AuthorizationPresetService:

  def getClientPresets(clientId: ClientId): Task[Vector[AuthorizationPreset]]

  def savePresets(request: SaveAuthorizationPresetsRequest): Task[Either[PresetValidationError, Unit]]

  def getPresetsForSync(edgeId: Option[EdgeId]): Task[Vector[AuthorizationPreset]]

  def sync(event: SyncEvent.PresetsUpdated): Task[Unit]

object AuthorizationPresetService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[AuthorizationPresetRepository & OAuthClientService & Scope, Throwable, AuthorizationPresetService] =
    ZLayer(ReloadingCache.make[Vector[AuthorizationPreset]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      cache: ReloadingCache[Vector[AuthorizationPreset]],
      repository: AuthorizationPresetRepository,
      clientService: OAuthClientService,
  ) extends AuthorizationPresetService:

    override def getClientPresets(clientId: ClientId): Task[Vector[AuthorizationPreset]] =
      cache.get.map(_.filter(p => p.clientId == clientId))

    override def savePresets(request: SaveAuthorizationPresetsRequest): Task[Either[PresetValidationError, Unit]] =
      (for
        client <- clientService.getAllClients
          .map(_.find(_.id == request.clientId))
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
            postLoginRedirectUri = presetRequest.postLoginRedirectUri,
            scope = presetRequest.scope,
            responseType = presetRequest.responseType,
            uiLocales = presetRequest.uiLocales,
            customParameters = presetRequest.customParameters,
            cookieDomain = presetRequest.cookieDomain,
            cookiePath = presetRequest.cookiePath,
          )

        _ <- repository.replace(request.clientId, presets)
      yield ())
        .either
        .flatMap {
          case Left(ex: Throwable) => ZIO.fail(ex)
          case Left(error: PresetValidationError) => ZIO.left(error)
          case Right(_) => ZIO.right(())
        }

    override def getPresetsForSync(edgeId: Option[EdgeId]): Task[Vector[AuthorizationPreset]] =
      edgeId match
        case None => cache.get
        case Some(_) =>
          for
            presets <- cache.get
            clients <- clientService.getClientsForSync(edgeId)
            allowedClientIds = clients.map(_.id).toSet
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
