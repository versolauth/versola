package versola.edge

import versola.edge.model.{AccessToken, PresetId}
import zio.Duration
import zio.http.{Cookie, Path}
import zio.json.JsonCodec

import java.time.Instant

object EdgeSessionCookie:
  val name = "EDGE_SESSION"

  inline def apply(
      accessToken: AccessToken,
      ttl: Duration,
      domain: Option[String],
      path: Option[String],
      now: Instant,
  ): Cookie.Response =
    Cookie.Response(
      name = name,
      content = accessToken,
      domain = domain,
      path = Some(path.map(Path.decode).getOrElse(Path.root)),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(ttl),
      sameSite = Some(Cookie.SameSite.Strict),
    )

  def clear(domain: Option[String], path: Option[String], now: Instant): Cookie.Response =
    Cookie.Response(
      name = name,
      content = "",
      domain = domain,
      path = Some(path.map(Path.decode).getOrElse(Path.root)),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(Duration.Zero),
      sameSite = Some(Cookie.SameSite.Strict),
    )
