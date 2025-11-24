package versola.oauth.model

import versola.oauth.conversation.model.AuthId
import zio.Duration
import zio.http.{Cookie, Path}

object ConversationCookie:
  val name = "SSO_CONVERSATION"

  inline def apply(value: AuthId, ttl: Duration): Cookie.Response = Cookie.Response(
    name = name,
    content = value.toString,
    domain = None,
    path = Some(Path.root / "api" / "v1" / "challenge"),
    isSecure = false,
    isHttpOnly = true,
    maxAge = Some(ttl),
    sameSite = None,
  )
