package versola.oauth.model

import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.AuthId
import versola.oauth.session.model.SessionId
import versola.util.Secret
import zio.*
import zio.json.*
import zio.test.*

import java.util.UUID

object CookiesSpec extends ZIOSpecDefault:

  private val authId = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  private val clientId = ClientId("test-client")
  private val secret = TestEnvConfig.coreConfig.security.conversationCookieSecret

  def spec = suite("CookiesSpec")(
    suite("ConversationCookie")(
      test("responseCookie creates a secure cookie with valid signature") {
        val cookie = ConversationCookie(authId, clientId)
        val resp   = ConversationCookie.responseCookie(cookie, 15.minutes, secret)
        assertTrue(
          resp.name == ConversationCookie.name,
          resp.path.nonEmpty,
          resp.isHttpOnly,
          resp.isSecure,
          resp.maxAge.contains(15.minutes)
        ) &&
        assertTrue(ConversationCookie.parse(resp.content, secret) == Right(cookie))
      },

      test("parse fails with wrong secret") {
        val cookie      = ConversationCookie(authId, clientId)
        val content     = ConversationCookie.responseCookie(cookie, 15.minutes, secret).content
        val wrongSecret = Secret.Bytes32(Array.fill(32)(9.toByte))
        assertTrue(ConversationCookie.parse(content, wrongSecret).isLeft)
      },

      test("parse fails with tampered payload") {
        val cookie          = ConversationCookie(authId, clientId)
        val content         = ConversationCookie.responseCookie(cookie, 15.minutes, secret).content
        val parts           = content.split('.')
        val tamperedPayload = "eyJhdXRoSWQiOiJiYmJiYmJiYi1iYmJiLWJiYmItYmJiYi1iYmJiYmJiYmJiYmIiLCJjbGllbnRJZCI6InRlc3QtY2xpZW50In0"
        val tamperedContent = s"$tamperedPayload.${parts(1)}"
        assertTrue(ConversationCookie.parse(tamperedContent, secret).isLeft)
      }
    ),

    suite("SessionCookie")(
      test("creates a session cookie with correct name and properties") {
        val sessionId = SessionId(Array.fill(32)(1.toByte))
        val cookie    = SessionCookie(sessionId, 1.hour)
        assertTrue(
          cookie.name == SessionCookie.name,
          cookie.isHttpOnly,
          cookie.isSecure,
          cookie.maxAge.contains(1.hour)
        )
      }
    )
  )
