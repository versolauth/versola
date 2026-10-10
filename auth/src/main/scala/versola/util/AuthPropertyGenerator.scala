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
    // 32 bytes, not 16. A code is presented in a redirect to claim a login, so RFC 6819 §5.1.4.2.2
    // asks for at least 128 bits, and 16 random bytes are exactly that. But the OpenID
    // Foundation conformance suite (EnsureMinimumAuthorizationCodeEntropy) estimates entropy
    // from the encoded string -- a Shannon estimate that repeated characters pull below its
    // 96-bit floor on a 22-character code, so a correct 128-bit code fails it at random
    // (observed: 92.1). Twice the length leaves the estimate far above the floor. A code is
    // short-lived and single-use, so the extra length costs nothing; codes issued at 16 bytes
    // before this still redeem, since nothing checks the length on the way in.
    override def nextAuthorizationCode: UIO[AuthorizationCode] =
      secureRandom.nextBytes(32).map(AuthorizationCode(_))

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
