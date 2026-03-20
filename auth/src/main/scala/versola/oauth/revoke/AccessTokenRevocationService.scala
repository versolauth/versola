package versola.oauth.revoke

import versola.oauth.model.{AccessToken, AccessTokenPayload}
import zio.{Task, UIO, ZIO, ZLayer}


trait AccessTokenRevocationService:
  def revoke(token: AccessToken): Task[Unit]
  def isActive(token: AccessToken): Task[Boolean]

object AccessTokenRevocationService:

  def noop: ZLayer[Any, Nothing, AccessTokenRevocationService] =
    ZLayer.succeed(NoopImpl())

  private class NoopImpl extends AccessTokenRevocationService:
    override def revoke(token: AccessToken): Task[Unit] =
      ZIO.unit

    override def isActive(token: AccessToken): Task[Boolean] =
      ZIO.succeed(true)


