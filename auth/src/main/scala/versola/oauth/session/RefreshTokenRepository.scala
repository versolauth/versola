package versola.oauth.session

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{RefreshAlreadyExchanged, SessionId, RefreshTokenRecord, WithTtl}
import versola.util.MAC
import zio.{IO, Task}
import zio.prelude.These

trait RefreshTokenRepository:
  def create(
      refreshToken: MAC.Of[RefreshToken],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit]

  def findRefreshToken(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]]
