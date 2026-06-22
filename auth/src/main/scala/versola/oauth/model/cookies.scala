package versola.oauth.model

import versola.oauth.conversation.model.AuthId
import versola.oauth.session.model.SessionId
import versola.util.Base64Url
import zio.Duration
import zio.http.{Cookie, Path}

object ConversationCookie:
  val name = "SSO_CONVERSATION"

  inline def apply(value: AuthId, ttl: Duration): Cookie.Response = Cookie.Response(
    name = name,
    content = value.toString,
    domain = None,
    path = Some(Path.root / "challenge"),
    isSecure = true,
    isHttpOnly = true,
    maxAge = Some(ttl),
    sameSite = None,
  )

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
