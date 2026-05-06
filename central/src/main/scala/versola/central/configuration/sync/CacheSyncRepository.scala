package versola.central.configuration.sync

import zio.stream.{Stream, ZStream}
import zio.{ULayer, ZLayer}

trait CacheSyncRepository:
  def getNotifications: Stream[Throwable, SyncEvent]

object CacheSyncRepository:
  def noop: ULayer[CacheSyncRepository] = ZLayer.succeed:
    new CacheSyncRepository:
      def getNotifications: Stream[Throwable, SyncEvent] = ZStream.empty
