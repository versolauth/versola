package versola.oauth.model

import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.AuthId
import versola.oauth.session.model.SessionId
import versola.util.Base64Url
import zio.Duration
import zio.http.{Cookie, Path}
import zio.json.*

import java.nio.charset.StandardCharsets

case class ConversationCookie(authId: AuthId, clientId: ClientId) derives JsonCodec

object ConversationCookie:
  val name = "SSO_CONVERSATION"

  def responseCookie(payload: ConversationCookie, ttl: Duration): Cookie.Response = Cookie.Response(
    name = name,
    content = Base64Url.encode(payload.toJson.getBytes(StandardCharsets.UTF_8)),
    domain = None,
    path = Some(Path.root / "challenge"),
    isSecure = true,
    isHttpOnly = true,
    maxAge = Some(ttl),
    sameSite = None,
  )

  def parse(content: String): Either[String, ConversationCookie] =
    scala.util.Try(Base64Url.decodeStr(content)).toEither.left.map(_.getMessage)
      .flatMap(_.fromJson[ConversationCookie])

object SessionCookie:
  val name = "SSO_SESSION"

  inline def apply(value: SessionId, ttl: Duration): Cookie.Response = Cookie.Response(
    name = name,
    content = Base64Url.encode(value),
    domain = None,
    path = Some(Path.root),
    isSecure = true,
    isHttpOnly = true,
    maxAge = Some(ttl),
    sameSite = None,
  )
