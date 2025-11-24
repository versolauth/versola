package versola.util

import versola.oauth.model.AuthorizationCode
import versola.oauth.session.model.SessionId
import versola.security.SecureRandom
import zio.{UIO, ZLayer}

trait AuthPropertyGenerator:
  def nextAuthorizationCode: UIO[AuthorizationCode]
  def nextSessionId: UIO[SessionId]

object AuthPropertyGenerator:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(secureRandom: SecureRandom) extends AuthPropertyGenerator:
    override def nextAuthorizationCode: UIO[AuthorizationCode] =
      secureRandom.nextBytes(32).map(AuthorizationCode(_))

    override def nextSessionId: UIO[SessionId] =
      secureRandom.nextBytes(32).map(SessionId(_))


