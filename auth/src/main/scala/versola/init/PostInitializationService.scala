package versola.init

import zio.*

trait PostInitializationService:
  def postInitialize(): Task[Unit]

object PostInitializationService:
  class Impl(
  ) extends PostInitializationService:

    override def postInitialize(): Task[Unit] = ZIO.unit
