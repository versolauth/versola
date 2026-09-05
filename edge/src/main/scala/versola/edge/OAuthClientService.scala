package versola.edge

import versola.edge.model.{AuthorizationPreset, ClientId, OAuthClient, PresetId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer}

trait OAuthClientService:
  def findPreset(presetId: PresetId): UIO[Option[AuthorizationPreset]]
  def listPresets(presetIds: List[PresetId]): UIO[List[AuthorizationPreset]]
  def findClient(clientId: ClientId): UIO[Option[OAuthClient]]

  /** Every client this edge knows about. Used where no single client identifies the answer,
    * such as bounding how long a revocation has to be remembered for a session whose
    * participating clients are no longer known.
    */
  def listClients: UIO[List[OAuthClient]]

  /** Reloads both caches from central now, instead of waiting for `configurationCacheRefreshInterval`.
    * Backs the non-prod `/service/configuration/sync` endpoint; nothing in request handling
    * calls this. */
  def refreshNow: Task[Unit]

object OAuthClientService:
  def live: ZLayer[
    AuthorizationPresetsSyncClient & OAuthClientsSyncClient & Scope & EdgeConfig,
    Throwable,
    OAuthClientService,
  ] =
    (
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[PresetId, AuthorizationPreset]](config.configurationCacheRefreshInterval),
        )
      ) ++
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[EdgeConfig](config =>
          ReloadingCache.make[Map[ClientId, OAuthClient]](config.configurationCacheRefreshInterval),
        )
      ) ++
      ZLayer.service[AuthorizationPresetsSyncClient] ++
      ZLayer.service[OAuthClientsSyncClient]
    ) >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      presetCache: ReloadingCache[Map[PresetId, AuthorizationPreset]],
      clientCache: ReloadingCache[Map[ClientId, OAuthClient]],
      presetSource: AuthorizationPresetsSyncClient,
      clientSource: OAuthClientsSyncClient,
  ) extends OAuthClientService:

    override def findPreset(presetId: PresetId): UIO[Option[AuthorizationPreset]] =
      presetCache.get.map(_.get(presetId))

    override def listPresets(presetIds: List[PresetId]): UIO[List[AuthorizationPreset]] =
      presetCache.get.map(cache => presetIds.flatMap(cache.get))

    override def findClient(clientId: ClientId): UIO[Option[OAuthClient]] =
      clientCache.get.map(_.get(clientId))

    override def listClients: UIO[List[OAuthClient]] =
      clientCache.get.map(_.values.toList)

    override def refreshNow: Task[Unit] =
      (presetSource.getAll.flatMap(presetCache.set) <&> clientSource.getAll.flatMap(clientCache.set)).unit
