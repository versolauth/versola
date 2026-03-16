package versola.util

import versola.cleanup.CleanupManager
import zio.*

trait PostInitializationService:
  def postInitialize(): Task[Unit]

object PostInitializationService:
  val layer: ZLayer[CleanupManager, Nothing, PostInitializationService] =
    ZLayer.fromFunction(Impl(_))

  class Impl(
      cleanupManager: CleanupManager,
  ) extends PostInitializationService:

    override def postInitialize(): Task[Unit] = ZIO.unit
