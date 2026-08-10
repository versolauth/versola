package versola.edge

import versola.edge.model.{AuthorizationPreset, ClientId, OAuthClient, PresetId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, UIO, ZIO, ZLayer}

trait OAuthClientService:
  def findPreset(presetId: PresetId): UIO[Option[AuthorizationPreset]]
  def listPresets(presetIds: List[PresetId]): UIO[List[AuthorizationPreset]]
  def findClient(clientId: ClientId): UIO[Option[OAuthClient]]

object OAuthClientService:
  def live: ZLayer[
    AuthorizationPresetsSyncClient & OAuthClientsSyncClient & Scope & EdgeConfig,
    Throwable,
    OAuthClientService,
  ] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[PresetId, AuthorizationPreset]](Schedule.spaced(config.configurationCacheRefreshInterval)),
        )
      ) ++
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[ClientId, OAuthClient]](Schedule.spaced(config.configurationCacheRefreshInterval)),
        )
      )
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      presetCache: ReloadingCache[Map[PresetId, AuthorizationPreset]],
      clientCache: ReloadingCache[Map[ClientId, OAuthClient]],
  ) extends OAuthClientService:

    override def findPreset(presetId: PresetId): UIO[Option[AuthorizationPreset]] =
      presetCache.get.map(_.get(presetId))

    override def listPresets(presetIds: List[PresetId]): UIO[List[AuthorizationPreset]] =
      presetCache.get.map(cache => presetIds.flatMap(cache.get))

    override def findClient(clientId: ClientId): UIO[Option[OAuthClient]] =
      clientCache.get.map(_.get(clientId))
