package versola.oauth.model

import org.apache.commons.codec.digest.Blake3
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.model.AuthId
import versola.oauth.session.model.SessionId
import versola.util.{Base64, Base64Url, Secret}
import zio.Duration
import zio.http.{Cookie, Path}
import zio.json.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

case class ConversationCookie(authId: AuthId, clientId: ClientId) derives JsonCodec

object ConversationCookie:
  val name = "SSO_CONVERSATION"

  /** Serialises the payload as JSON, computes a Blake3 keyed-hash over those bytes,
   *  and produces a cookie whose content is `base64url(json) + "." + base64url(mac)`.
   */
  def responseCookie(payload: ConversationCookie, ttl: Duration, secret: Secret.Bytes32): Cookie.Response =
    val payloadBytes = payload.toJson.getBytes(StandardCharsets.UTF_8)
    val payloadB64   = Base64.urlEncode(payloadBytes)
    val sigB64       = Base64.urlEncode(computeMac(payloadBytes, secret))
    Cookie.Response(
      name = name,
      content = s"$payloadB64.$sigB64",
      domain = None,
      path = Some(Path.root / "challenge"),
      isSecure = true,
      isHttpOnly = true,
      maxAge = Some(ttl),
      sameSite = None,
    )

  /** Parses and HMAC-verifies a cookie produced by [[responseCookie]].
   *  Returns [[Left]] if the content is malformed or the signature does not match.
   */
  def parse(content: String, secret: Secret.Bytes32): Either[String, ConversationCookie] =
    val dotIdx = content.lastIndexOf('.')
    if dotIdx < 0 then Left("missing signature")
    else
      val payloadB64 = content.substring(0, dotIdx)
      val sigB64     = content.substring(dotIdx + 1)
      for
        payloadBytes <- scala.util.Try(Base64.urlDecode(payloadB64)).toEither.left.map(_.getMessage)
        sigBytes     <- scala.util.Try(Base64.urlDecode(sigB64)).toEither.left.map(_.getMessage)
        _            <- Either.cond(MessageDigest.isEqual(computeMac(payloadBytes, secret), sigBytes), (), "invalid signature")
        cookie       <- new String(payloadBytes, StandardCharsets.UTF_8).fromJson[ConversationCookie]
      yield cookie

  private def computeMac(data: Array[Byte], key: Secret.Bytes32): Array[Byte] =
    val mac = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(key).update(data).doFinalize(mac)
    mac

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
