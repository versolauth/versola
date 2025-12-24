package versola.oauth.session

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.session.model.{SessionId, TokenRecord, WithTtl}
import versola.util.MAC
import zio.Task
import zio.prelude.These

trait TokenRepository:
  def create(
      tokens: These[WithTtl[MAC.Of[AccessToken]], WithTtl[MAC.Of[RefreshToken]]],
      record: TokenRecord,
  ): Task[Unit]

  def findAccessToken(token: MAC.Of[AccessToken]): Task[Option[TokenRecord]]

  def findRefreshToken(token: MAC.Of[RefreshToken]): Task[Option[TokenRecord]]
