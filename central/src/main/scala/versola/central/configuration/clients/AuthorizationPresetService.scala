package versola.central.configuration.clients

import versola.central.CentralConfig
import versola.central.configuration.{AuthorizationPresetInput, SaveAuthorizationPresetsRequest}
import versola.central.configuration.challenges.ChallengeSettingsService
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

trait AuthorizationPresetService:

  def getClientPresets(clientId: ClientId): Task[Vector[AuthorizationPreset]]

  def savePresets(request: SaveAuthorizationPresetsRequest): Task[Either[PresetValidationError, Unit]]

  def getPresetsForSync(edgeId: Option[EdgeId]): Task[Vector[AuthorizationPreset]]

  def sync(event: SyncEvent.PresetsUpdated): Task[Unit]

object AuthorizationPresetService:
  def live: ZLayer[
    AuthorizationPresetRepository & OAuthClientService & ChallengeSettingsService & Scope & CentralConfig,
    Throwable,
    AuthorizationPresetService,
  ] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[AuthorizationPreset]](config.configurationCacheRefreshInterval),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      cache: ReloadingCache[Vector[AuthorizationPreset]],
      repository: AuthorizationPresetRepository,
      clientService: OAuthClientService,
      challengeSettingsService: ChallengeSettingsService,
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

        _ <- validatePresetIds(request.clientId, request.presets)

        presets = request.presets.map: presetRequest =>
          AuthorizationPreset(
            id = presetRequest.id,
            clientId = request.clientId,
            description = presetRequest.description,
            redirectUri = presetRequest.redirectUri,
            postLoginRedirectUri = presetRequest.postLoginRedirectUri,
            postLogoutRedirectUri = presetRequest.postLogoutRedirectUri,
            scope = presetRequest.scope,
            responseType = presetRequest.responseType,
            uiLocales = presetRequest.uiLocales,
            customParameters = presetRequest.customParameters,
            cookieDomain = presetRequest.cookieDomain,
            cookiePath = presetRequest.cookiePath,
          )

        _ <- repository.replace(request.clientId, presets)
        _ <- registerPostLogoutRedirectUris(client.tenantId)
      yield ())
        .either
        .flatMap {
          case Left(error: PresetValidationError) => ZIO.left(error)
          case Left(ex: Throwable) => ZIO.fail(ex)
          case Right(_) => ZIO.right(())
        }

    private def validatePresetIds(clientId: ClientId, presets: List[AuthorizationPresetInput]): Task[Unit] =
      if presets.isEmpty then ZIO.unit
      else
        for
          _ <- ZIO.fail(PresetValidationError.DuplicatePresetId).when(presets.map(_.id).distinct.size != presets.size)
          existing <- repository.getAll
          _ <- ZIO.fail(PresetValidationError.DuplicatePresetId).when(
            presets.exists(preset => existing.exists(existingPreset =>
              existingPreset.id == preset.id && existingPreset.clientId != clientId
            )),
          )
        yield ()

    /** Ensures every `postLogoutRedirectUri` currently used by a preset of the tenant is present
      * in the tenant's `ChallengeSettingsRecord.postLogoutRedirectUris` allow-list, so the auth
      * server accepts it during RP-initiated logout.
      */
    private def registerPostLogoutRedirectUris(tenantId: TenantId): Task[Unit] =
      for
        clients <- clientService.getAllClients
        tenantClientIds = clients.filter(_.tenantId == tenantId).map(_.id).toSet
        allPresets <- repository.getAll
        usedUris = allPresets.view
          .filter(preset => tenantClientIds.contains(preset.clientId))
          .flatMap(_.postLogoutRedirectUri)
          .map(uri => uri: String)
          .toSet
        _ <- ZIO.unless(usedUris.isEmpty)(addToPostLogoutRedirectUriAllowlist(tenantId, usedUris))
      yield ()

    private def addToPostLogoutRedirectUriAllowlist(tenantId: TenantId, usedUris: Set[String]): Task[Unit] =
      challengeSettingsService.getSettings(tenantId).flatMap {
        case Some(settings) =>
          val merged = (settings.postLogoutRedirectUris ++ usedUris).distinct
          ZIO.when(merged != settings.postLogoutRedirectUris)(
            challengeSettingsService.upsertSettings(settings.copy(postLogoutRedirectUris = merged)),
          ).unit
        case None => ZIO.unit
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
  case object DuplicatePresetId
      extends RuntimeException("Authorization preset ID is already used by another client")
      with PresetValidationError
  case object ClientNotFound extends PresetValidationError
  case object InvalidRedirectUri extends PresetValidationError
  case object InvalidPostLogoutRedirectUri extends PresetValidationError
  case object InvalidScope extends PresetValidationError
