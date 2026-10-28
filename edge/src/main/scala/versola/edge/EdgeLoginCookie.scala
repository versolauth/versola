package versola.edge

import versola.edge.model.State
import zio.Duration
import zio.http.{Cookie, Path}

/** The browser half of [[versola.edge.login.BrowserBinding]]. One cookie per pending login, named by its
  * state, so a second login begun in another tab or for another preset does not replace the secret of the
  * first. It is only sent to the path `/complete` is served at, and lives as long as the login does.
  *
  * `SameSite=Lax`, not `Strict` like [[EdgeSessionCookie]]: `/complete` is reached by the OP's redirect, a
  * cross-site top-level GET, which a `Strict` cookie does not accompany, so every login would be refused.
  * `Lax` still withholds it from cross-site subrequests and from any non-GET request.
  *
  * Known cost of one cookie per login: `/login` is unauthenticated, so a client that loops on it (a stale
  * `redirect_uri`, a proxy that drops `Set-Cookie`) piles up one cookie per call in a browser for the
  * `Max-Age` of 10 minutes. Browsers cap cookies per domain and evict the least recently used first, which
  * can include `EDGE_SESSION`, and `/complete` carries them all. Accepted: it only affects such loops and is
  * bounded by the TTL. If it is seen in the field, keep one cookie holding the last few `state.secret` pairs.
  */
object EdgeLoginCookie:
  private val prefix = "EDGE_LOGIN_"

  def name(state: State): String = s"$prefix$state"

  def apply(state: State, secret: String, path: Path, ttl: Duration): Cookie.Response =
    Cookie.Response(
      name = name(state),
      content = secret,
      path = Some(path),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(ttl),
      sameSite = Some(Cookie.SameSite.Lax),
    )

  def clear(state: State, path: Path): Cookie.Response =
    Cookie.Response(
      name = name(state),
      content = "",
      path = Some(path),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(Duration.Zero),
      sameSite = Some(Cookie.SameSite.Lax),
    )
