package versola.util

import versola.oauth.model.{AccessToken, AuthorizationCode, RefreshToken}
import versola.oauth.session.model.{PublicSessionId, RefreshTokenFamilyId, SessionId}
import zio.{UIO, ZLayer}

trait AuthPropertyGenerator:
  def nextAuthorizationCode: UIO[AuthorizationCode]
  def nextSessionId: UIO[SessionId]
  def nextPublicSessionId: UIO[PublicSessionId]
  def nextAccessToken: UIO[AccessToken]
  def nextRefreshToken: UIO[RefreshToken]
  def nextRefreshTokenFamilyId: UIO[RefreshTokenFamilyId]

object AuthPropertyGenerator:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(secureRandom: SecureRandom) extends AuthPropertyGenerator:
    override def nextAuthorizationCode: UIO[AuthorizationCode] =
      secureRandom.nextBytes(16).map(AuthorizationCode(_))

    override def nextSessionId: UIO[SessionId] =
      secureRandom.nextBytes(32).map(SessionId(_))

    override def nextPublicSessionId: UIO[PublicSessionId] =
      secureRandom.nextBytes(16).map(PublicSessionId.fromBytes)

    override def nextAccessToken: UIO[AccessToken] =
      secureRandom.nextBytes(16).map(AccessToken(_))

    override def nextRefreshToken: UIO[RefreshToken] =
      secureRandom.nextBytes(32).map(RefreshToken(_))

    // 16 bytes, like the public session id and unlike the refresh token itself: this names a
    // family, it is not presented to claim one, so it needs to be unguessable only in the
    // sense that it must not be enumerable.
    override def nextRefreshTokenFamilyId: UIO[RefreshTokenFamilyId] =
      secureRandom.nextBytes(16).map(RefreshTokenFamilyId.fromBytes)




