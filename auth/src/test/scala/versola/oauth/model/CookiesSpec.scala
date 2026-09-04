package versola.oauth.model

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.AuthId
import versola.oauth.session.model.{SessionId, UserAgentDetails, UserAgentId}
import versola.user.model.UserId
import versola.util.Base64
import versola.util.Secret
import zio.*
import zio.json.*
import zio.test.*

import java.util.UUID

object CookiesSpec extends ZIOSpecDefault:

  private val authId = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  private val clientId = ClientId("test-client")
  private val secret = TestEnvConfig.coreConfig.security.conversationCookieSecret
  private val sessionSecret = TestEnvConfig.coreConfig.security.sessionCookieSecret

  private val userAgentId = UserAgentId(UUID.fromString("018f0f2a-1c7b-7000-8000-000000000001"))
  private val userAgentData = UserAgentData(
    userAgent = Some("Mozilla/5.0"),
    userId = UserId(UUID.fromString("00000000-0000-7000-8000-000000000001")),
    details = UserAgentDetails(Some("desktop"), Some("macOS"), Some("Chrome"), Some("125")),
  )

  def spec = suite("CookiesSpec")(
    suite("ConversationCookie")(
      test("responseCookie creates a secure cookie with valid signature") {
        val cookie = ConversationCookie(authId, clientId, "https://example.com/callback", None)
        val resp = ConversationCookie.responseCookie(cookie, 15.minutes, secret)
        assertTrue(
          resp.name == ConversationCookie.name,
          resp.path.nonEmpty,
          resp.isHttpOnly,
          resp.isSecure,
          resp.maxAge.contains(15.minutes),
        ) &&
        assertTrue(ConversationCookie.parse(resp.content, secret) == Right(cookie))
      },
      test("round-trips the redirect context used by the expired page") {
        val cookie = ConversationCookie(
          authId,
          clientId,
          redirectUri = "https://example.com/callback",
          state = Some("state-1"),
        )
        val content = ConversationCookie.responseCookie(cookie, 15.minutes, secret).content
        assertTrue(ConversationCookie.parse(content, secret) == Right(cookie))
      },
      test("parse fails with wrong secret") {
        val cookie = ConversationCookie(authId, clientId, "https://example.com/callback", None)
        val content = ConversationCookie.responseCookie(cookie, 15.minutes, secret).content
        val wrongSecret = Secret.Bytes32(Array.fill(32)(9.toByte))
        assertTrue(ConversationCookie.parse(content, wrongSecret).isLeft)
      },
      test("parse fails with tampered payload") {
        val cookie = ConversationCookie(authId, clientId, "https://example.com/callback", None)
        val content = ConversationCookie.responseCookie(cookie, 15.minutes, secret).content
        val parts = content.split('.')
        val tamperedPayload = "eyJhdXRoSWQiOiJiYmJiYmJiYi1iYmJiLWJiYmItYmJiYi1iYmJiYmJiYmJiYmIiLCJjbGllbnRJZCI6InRlc3QtY2xpZW50In0"
        val tamperedContent = s"$tamperedPayload.${parts(1)}"
        assertTrue(ConversationCookie.parse(tamperedContent, secret).isLeft)
      },
    ),
    suite("SessionCookie")(
      test("creates a session cookie with valid signature") {
        val sessionId = SessionId(Array.fill(32)(1.toByte))
        val cookie = SessionCookie(sessionId, 1.hour, sessionSecret)
        assertTrue(
          cookie.name == SessionCookie.name,
          cookie.isHttpOnly,
          cookie.isSecure,
          cookie.maxAge.contains(1.hour),
        ) &&
        assertTrue(SessionCookie.parse(cookie.content, sessionSecret).map(_.toSeq) == Right(sessionId.toSeq))
      },
      test("parse fails with wrong secret") {
        val sessionId = SessionId(Array.fill(32)(1.toByte))
        val content = SessionCookie(sessionId, 1.hour, sessionSecret).content
        val wrongSecret = Secret.Bytes32(Array.fill(32)(9.toByte))
        assertTrue(SessionCookie.parse(content, wrongSecret).isLeft)
      },
      test("parse fails with tampered content") {
        val sessionId = SessionId(Array.fill(32)(1.toByte))
        val content = SessionCookie(sessionId, 1.hour, sessionSecret).content
        val parts = content.split('.')
        val tamperedPayload = Base64.urlEncode(Array.fill(32)(2.toByte))
        val tamperedContent = s"$tamperedPayload.${parts(1)}"
        assertTrue(SessionCookie.parse(tamperedContent, sessionSecret).isLeft)
      },
      test("parse rejects a payload that is not a 32-byte session id") {
        val shortPayload = Base64.urlEncode(Array.fill(8)(1.toByte))
        val content = SessionCookie(SessionId(Array.fill(8)(1.toByte)), 1.hour, sessionSecret).content
        assertTrue(
          SessionCookie.parse(content, sessionSecret) == Left("invalid session id length"),
          shortPayload.nonEmpty,
        )
      },
      test("parse rejects content with no signature separator") {
        assertTrue(SessionCookie.parse("no-signature-here", sessionSecret) == Left("missing signature"))
      },
    ),
    suite("UserAgentCookie")(
      test("round-trips the device id and the user agent seen when it was issued") {
        val cookie = UserAgentCookie(userAgentId, userAgentData, 180.days, secret)
        assertTrue(
          cookie.name == UserAgentCookie.name,
          cookie.isHttpOnly,
          cookie.isSecure,
          cookie.maxAge.contains(180.days),
          UserAgentCookie.parse(cookie.content, secret) ==
            Right(UserAgentCookiePayload(userAgentId, userAgentData)),
        )
      },
      test("parse fails with wrong secret") {
        val content = UserAgentCookie(userAgentId, userAgentData, 180.days, secret).content
        val wrongSecret = Secret.Bytes32(Array.fill(32)(9.toByte))
        assertTrue(UserAgentCookie.parse(content, wrongSecret) == Left("invalid signature"))
      },
      test("parse fails when the payload is swapped for another signed value") {
        val content = UserAgentCookie(userAgentId, userAgentData, 180.days, secret).content
        val other = UserAgentCookie(
          userAgentId,
          userAgentData.copy(userAgent = Some("curl/8")),
          180.days,
          secret,
        ).content
        val tampered = s"${other.split('.')(0)}.${content.split('.')(1)}"
        assertTrue(UserAgentCookie.parse(tampered, secret).isLeft)
      },
      test("parse rejects content with no signature separator") {
        assertTrue(UserAgentCookie.parse("no-signature-here", secret) == Left("missing signature"))
      },
      test("parse rejects a signed payload that is not the expected JSON shape") {
        // Signed with the right key, so this gets past the MAC check and fails on decoding.
        val bytes = """{"unexpected":true}""".getBytes("UTF-8").nn
        val signed = UserAgentCookie(userAgentId, userAgentData, 180.days, secret).content
        val tampered = s"${Base64.urlEncode(bytes)}.${signed.split('.')(1)}"
        assertTrue(UserAgentCookie.parse(tampered, secret).isLeft)
      },
    ),
    suite("malformed content")(
      test("ConversationCookie.parse rejects content with no signature separator") {
        assertTrue(ConversationCookie.parse("no-signature-here", secret) == Left("missing signature"))
      },
      test("ConversationCookie.parse rejects a payload that is not base64url") {
        val content = ConversationCookie.responseCookie(
          ConversationCookie(authId, clientId, "https://example.com/callback", None),
          15.minutes,
          secret,
        ).content
        assertTrue(ConversationCookie.parse(s"not base64!.${content.split('.')(1)}", secret).isLeft)
      },
    ),
  )
