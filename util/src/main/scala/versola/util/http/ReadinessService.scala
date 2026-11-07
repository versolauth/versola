package versola.util.http

import zio.*

trait ReadinessService:
  def isReady: UIO[Boolean]
  def setReady: UIO[Unit]

  /** Takes the service out of rotation: from the start of a shutdown, so that a balancer stops sending it requests while it
    * drains, instead of finding out when the port closes.
    */
  def setNotReady: UIO[Unit]

object ReadinessService:
  val make =
    for
      ref <- Ref.make(false)
    yield new ReadinessService:
      def isReady: UIO[Boolean] = ref.get
      def setReady: UIO[Unit] = ref.set(true)
      def setNotReady: UIO[Unit] = ref.set(false)
