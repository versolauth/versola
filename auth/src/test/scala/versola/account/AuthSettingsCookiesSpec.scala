package versola.account

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object AuthSettingsCookiesSpec extends UnitSpecBase:

  private val secret   = TestEnvConfig.coreConfig.security.conversationCookieSecret
  private val userId   = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val clientId = ClientId("00000000-0000-0000-0000-000000000002")

  def spec = suite("AuthSettingsCookies")(

    suite("AuthSettingsCookie")(

      test("parse accepts a freshly-issued cookie") {
        val payload = AuthSettingsCookie(userId, clientId)
        val content = AuthSettingsCookie.responseCookie(payload, secret).content
        assertTrue(AuthSettingsCookie.parse(content, secret) == Right(payload))
      },

      test("parse rejects a cookie with an invalid signature") {
        val payload  = AuthSettingsCookie(userId, clientId)
        val content  = AuthSettingsCookie.responseCookie(payload, secret).content
        val tampered = content.dropRight(4) + "XXXX"
        assertTrue(AuthSettingsCookie.parse(tampered, secret).isLeft)
      },

      test("parse rejects a cookie older than the TTL") {
        val payload     = AuthSettingsCookie(userId, clientId)
        val expiredAt   = Instant.now().getEpochSecond - AuthSettingsCookie.ttl.toSeconds - 1
        val content     = CookieSigning.encodeAt(payload.toJson, secret, expiredAt)
        assertTrue(AuthSettingsCookie.parse(content, secret) == Left("cookie expired"))
      },

    ),

    suite("PasskeyRegistrationCookie")(

      test("parse accepts a freshly-issued cookie") {
        val payload = PasskeyRegistrationCookie("ceremony-request-json")
        val content = PasskeyRegistrationCookie.responseCookie(payload, secret).content
        assertTrue(PasskeyRegistrationCookie.parse(content, secret) == Right(payload))
      },

      test("parse rejects a cookie with an invalid signature") {
        val payload  = PasskeyRegistrationCookie("ceremony-request-json")
        val content  = PasskeyRegistrationCookie.responseCookie(payload, secret).content
        val tampered = content.dropRight(4) + "XXXX"
        assertTrue(PasskeyRegistrationCookie.parse(tampered, secret).isLeft)
      },

      test("parse rejects a cookie older than the TTL") {
        val payload   = PasskeyRegistrationCookie("ceremony-request-json")
        val expiredAt = Instant.now().getEpochSecond - PasskeyRegistrationCookie.ttl.toSeconds - 1
        val content   = CookieSigning.encodeAt(payload.toJson, secret, expiredAt)
        assertTrue(PasskeyRegistrationCookie.parse(content, secret) == Left("cookie expired"))
      },

    ),

  )
