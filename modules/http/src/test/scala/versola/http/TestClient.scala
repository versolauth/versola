package versola.http

import zio.http.*
import zio.{ULayer, ZIO, ZLayer}

object TestClient:
  def addRoutes[R](routes: Routes[R, Response]): ZIO[R & TestServer, Nothing, Unit] =
    TestServer.addRoutes[R](routes)

  val layer: ZLayer[Any, Throwable, Client & TestServer] =
    ZLayer.make[Client & TestServer](
      Client.default,
      TestServer.default
    )
