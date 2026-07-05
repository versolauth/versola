package versola.account

import org.apache.commons.codec.digest.Blake3
import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import versola.util.{Base64, Secret}
import zio.Duration
import zio.http.{Cookie, Path}
import zio.json.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// AuthSettingsCookie — identifies the authenticated user on the account page.
// Set by GET /auth-settings (after Bearer token validation) and verified by
// every subsequent action endpoint.
// ---------------------------------------------------------------------------

case class AuthSettingsCookie(userId: UserId, clientId: ClientId) derives JsonCodec

object AuthSettingsCookie:
  val name = "SSO_ACCOUNT"
  val ttl: Duration = Duration.fromSeconds(3600) // 1 hour

  def responseCookie(payload: AuthSettingsCookie, secret: Secret.Bytes32): Cookie.Response =
    val payloadBytes = payload.toJson.getBytes(StandardCharsets.UTF_8)
    val payloadB64   = Base64.urlEncode(payloadBytes)
    val sigB64       = Base64.urlEncode(computeMac(payloadBytes, secret))
    Cookie.Response(
      name     = name,
      content  = s"$payloadB64.$sigB64",
      domain   = None,
      path     = Some(Path.root / "auth-settings"),
      isSecure = true,
      isHttpOnly = true,
      maxAge   = Some(ttl),
      sameSite = Some(Cookie.SameSite.Lax),
    )

  def parse(content: String, secret: Secret.Bytes32): Either[String, AuthSettingsCookie] =
    val dotIdx = content.lastIndexOf('.')
    if dotIdx < 0 then Left("missing signature")
    else
      val payloadB64 = content.substring(0, dotIdx)
      val sigB64     = content.substring(dotIdx + 1)
      for
        payloadBytes <- scala.util.Try(Base64.urlDecode(payloadB64)).toEither.left.map(_.getMessage)
        sigBytes     <- scala.util.Try(Base64.urlDecode(sigB64)).toEither.left.map(_.getMessage)
        _            <- Either.cond(MessageDigest.isEqual(computeMac(payloadBytes, secret), sigBytes), (), "invalid signature")
        cookie       <- new String(payloadBytes, StandardCharsets.UTF_8).fromJson[AuthSettingsCookie]
      yield cookie

  private def computeMac(data: Array[Byte], key: Secret.Bytes32): Array[Byte] =
    val mac = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(key).update(data).doFinalize(mac)
    mac

// ---------------------------------------------------------------------------
// PasskeyRegistrationCookie — holds the in-progress WebAuthn ceremony request.
// Set by GET /auth-settings (when passkey settings are available) and consumed
// by POST /auth-settings/passkeys/register.
// ---------------------------------------------------------------------------

case class PasskeyRegistrationCookie(request: String) derives JsonCodec

object PasskeyRegistrationCookie:
  val name = "SSO_PASSKEY_REG"
  val ttl: Duration = Duration.fromSeconds(300) // 5 minutes — matches WebAuthn ceremony timeout

  def responseCookie(payload: PasskeyRegistrationCookie, secret: Secret.Bytes32): Cookie.Response =
    val payloadBytes = payload.toJson.getBytes(StandardCharsets.UTF_8)
    val payloadB64   = Base64.urlEncode(payloadBytes)
    val sigB64       = Base64.urlEncode(computeMac(payloadBytes, secret))
    Cookie.Response(
      name     = name,
      content  = s"$payloadB64.$sigB64",
      domain   = None,
      path     = Some(Path.root / "auth-settings"),
      isSecure = true,
      isHttpOnly = true,
      maxAge   = Some(ttl),
      sameSite = Some(Cookie.SameSite.Lax),
    )

  def clear(secret: Secret.Bytes32): Cookie.Response =
    Cookie.Response(
      name     = name,
      content  = "",
      domain   = None,
      path     = Some(Path.root / "auth-settings"),
      isSecure = true,
      isHttpOnly = true,
      maxAge   = Some(Duration.Zero),
      sameSite = Some(Cookie.SameSite.Lax),
    )

  def parse(content: String, secret: Secret.Bytes32): Either[String, PasskeyRegistrationCookie] =
    val dotIdx = content.lastIndexOf('.')
    if dotIdx < 0 then Left("missing signature")
    else
      val payloadB64 = content.substring(0, dotIdx)
      val sigB64     = content.substring(dotIdx + 1)
      for
        payloadBytes <- scala.util.Try(Base64.urlDecode(payloadB64)).toEither.left.map(_.getMessage)
        sigBytes     <- scala.util.Try(Base64.urlDecode(sigB64)).toEither.left.map(_.getMessage)
        _            <- Either.cond(MessageDigest.isEqual(computeMac(payloadBytes, secret), sigBytes), (), "invalid signature")
        cookie       <- new String(payloadBytes, StandardCharsets.UTF_8).fromJson[PasskeyRegistrationCookie]
      yield cookie

  private def computeMac(data: Array[Byte], key: Secret.Bytes32): Array[Byte] =
    val mac = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(key).update(data).doFinalize(mac)
    mac
