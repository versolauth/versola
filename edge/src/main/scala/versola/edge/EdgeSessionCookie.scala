package versola.edge

import versola.edge.model.{AccessToken, PresetId}
import zio.Duration
import zio.http.{Cookie, Path}
import zio.json.JsonCodec

import java.time.Instant

object EdgeSessionCookie:
  val name = "EDGE_SESSION"

  inline def apply(
      presetId: PresetId,
      accessToken: AccessToken,
      ttl: Duration,
      domain: Option[String],
      path: Option[String],
      now: Instant,
  ): Cookie.Response =
    Cookie.Response(
      name = name,
      content = s"$presetId:$accessToken",
      domain = domain,
      path = Some(path.map(Path.decode).getOrElse(Path.root)),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(ttl),
      sameSite = Some(Cookie.SameSite.Strict),
    )

  /** Splits the cookie content into its preset id and access token. Content is
    * stored as "<presetId>:<accessToken>"; the access token (a JWT) never
    * contains a colon, so splitting on the first one is unambiguous. Legacy
    * content without a colon yields an empty preset id. */
  def parse(content: String): (PresetId, AccessToken) =
    content.indexOf(':') match
      case -1 => (PresetId(""), AccessToken(content))
      case i  => (PresetId(content.substring(0, i)), AccessToken(content.substring(i + 1)))

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
