package versola.util

import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.model.AuthorizationCode
import versola.oauth.session.model.SessionId
import zio.{UIO, ZLayer}

trait AuthPropertyGenerator:
  def nextAuthorizationCode: UIO[AuthorizationCode]
  def nextSessionId: UIO[SessionId]
  def nextAccessToken: UIO[AccessToken]
  def nextRefreshToken: UIO[RefreshToken]

object AuthPropertyGenerator:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(secureRandom: SecureRandom) extends AuthPropertyGenerator:
    override def nextAuthorizationCode: UIO[AuthorizationCode] =
      secureRandom.nextBytes(16).map(AuthorizationCode(_))

    override def nextSessionId: UIO[SessionId] =
      secureRandom.nextBytes(32).map(SessionId(_))

    override def nextAccessToken: UIO[AccessToken] =
      secureRandom.nextBytes(16).map(AccessToken(_))

    override def nextRefreshToken: UIO[RefreshToken] =
      secureRandom.nextBytes(32).map(RefreshToken(_))




