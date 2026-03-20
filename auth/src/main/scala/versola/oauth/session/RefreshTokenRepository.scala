package versola.oauth.session

import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{RefreshAlreadyExchanged, SessionId, RefreshTokenRecord, WithTtl}
import versola.util.MAC
import zio.{IO, Task}
import zio.prelude.These

trait RefreshTokenRepository:
  def create(
      refreshToken: MAC.Of[RefreshToken],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit]

  def find(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]]

  def delete(token: MAC.Of[RefreshToken]): Task[Unit]

  def deleteByAccessToken(token: AccessToken): Task[Unit]