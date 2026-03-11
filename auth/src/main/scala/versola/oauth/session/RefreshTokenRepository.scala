package versola.oauth.session

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{SessionId, TokenCreationRecord, TokenRecord, WithTtl}
import versola.util.MAC
import zio.Task
import zio.prelude.These

trait RefreshTokenRepository:
  def create(
      refreshToken: MAC.Of[RefreshToken],
      refreshTokenTtl: zio.Duration,
      record: TokenCreationRecord,
  ): Task[Unit]

  def findRefreshToken(token: MAC.Of[RefreshToken]): Task[Option[TokenRecord]]
