package versola.http

import zio.*


trait ReadinessService:
  def isReady: UIO[Boolean]
  def setReady: UIO[Unit]


object ReadinessService:
  val make =
    for
      ref <- Ref.make(false)
    yield new ReadinessService:
      def isReady: UIO[Boolean] = ref.get
      def setReady: UIO[Unit] = ref.set(true)





