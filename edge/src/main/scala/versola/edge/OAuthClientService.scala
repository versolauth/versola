package versola.edge

import versola.edge.model.{AuthorizationPreset, ClientId, OAuthClient, PresetId}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, UIO, ZLayer}

trait OAuthClientService:
  def findPreset(presetId: PresetId): UIO[Option[AuthorizationPreset]]
  def findClient(clientId: ClientId): UIO[Option[OAuthClient]]

object OAuthClientService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[
    AuthorizationPresetsSyncClient & OAuthClientsSyncClient & Scope,
    Throwable,
    OAuthClientService,
  ] =
    (
      ZLayer(ReloadingCache.make[Map[PresetId, AuthorizationPreset]](schedule)) ++
      ZLayer(ReloadingCache.make[Map[ClientId, OAuthClient]](schedule))
    ) >>> ZLayer.fromFunction(Impl(_, _))

  class Impl(
      presetCache: ReloadingCache[Map[PresetId, AuthorizationPreset]],
      clientCache: ReloadingCache[Map[ClientId, OAuthClient]],
  ) extends OAuthClientService:

    override def findPreset(presetId: PresetId): UIO[Option[AuthorizationPreset]] =
      presetCache.get.map(_.get(presetId))

    override def findClient(clientId: ClientId): UIO[Option[OAuthClient]] =
      clientCache.get.map(_.get(clientId))
