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
import java.time.Instant

// Signed envelope that wraps any cookie payload together with an issuedAt
// timestamp.  The HMAC covers the entire envelope JSON, so the timestamp
// cannot be stripped or tampered with independently of the payload.
private[account] case class CookieEnvelope(json: String, issuedAt: Long) derives JsonCodec

// Shared signing helpers used by both cookie companion objects.
private[account] object CookieSigning:

  /** Encode a payload as a signed cookie content string. */
  def encode(payloadJson: String, key: Secret.Bytes32): String =
    encodeAt(payloadJson, key, Instant.now().getEpochSecond)

  /** Encode with an explicit issuedAt; used only in tests to simulate stale cookies. */
  private[account] def encodeAt(payloadJson: String, key: Secret.Bytes32, issuedAt: Long): String =
    val envelope  = CookieEnvelope(payloadJson, issuedAt)
    val envBytes  = envelope.toJson.getBytes(StandardCharsets.UTF_8)
    val envB64    = Base64.urlEncode(envBytes)
    val sigB64    = Base64.urlEncode(mac(envBytes, key))
    s"$envB64.$sigB64"

  /** Decode and verify a signed cookie content string.
   *  Returns Left if the signature is invalid or the cookie is older than maxAgeSecs. */
  def decode(content: String, key: Secret.Bytes32, maxAgeSecs: Long): Either[String, String] =
    val dotIdx = content.lastIndexOf('.')
    if dotIdx < 0 then Left("missing signature")
    else
      val envB64 = content.substring(0, dotIdx)
      val sigB64 = content.substring(dotIdx + 1)
      for
        envBytes <- scala.util.Try(Base64.urlDecode(envB64)).toEither.left.map(_.getMessage)
        sigBytes <- scala.util.Try(Base64.urlDecode(sigB64)).toEither.left.map(_.getMessage)
        _        <- Either.cond(MessageDigest.isEqual(mac(envBytes, key), sigBytes), (), "invalid signature")
        envelope <- new String(envBytes, StandardCharsets.UTF_8).fromJson[CookieEnvelope]
        age       = Instant.now().getEpochSecond - envelope.issuedAt
        _        <- Either.cond(age <= maxAgeSecs && age >= -5, (), "cookie expired")
      yield envelope.json

  private def mac(data: Array[Byte], key: Secret.Bytes32): Array[Byte] =
    val out = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(key).update(data).doFinalize(out)
    out

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
    Cookie.Response(
      name       = name,
      content    = CookieSigning.encode(payload.toJson, secret),
      domain     = None,
      path       = Some(Path.root / "auth-settings"),
      isSecure   = true,
      isHttpOnly = true,
      maxAge     = Some(ttl),
      sameSite   = Some(Cookie.SameSite.Lax),
    )

  def parse(content: String, secret: Secret.Bytes32): Either[String, AuthSettingsCookie] =
    CookieSigning.decode(content, secret, ttl.toSeconds).flatMap(_.fromJson[AuthSettingsCookie])

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
    Cookie.Response(
      name       = name,
      content    = CookieSigning.encode(payload.toJson, secret),
      domain     = None,
      path       = Some(Path.root / "auth-settings"),
      isSecure   = true,
      isHttpOnly = true,
      maxAge     = Some(ttl),
      sameSite   = Some(Cookie.SameSite.Lax),
    )

  def clear(secret: Secret.Bytes32): Cookie.Response =
    Cookie.Response(
      name       = name,
      content    = "",
      domain     = None,
      path       = Some(Path.root / "auth-settings"),
      isSecure   = true,
      isHttpOnly = true,
      maxAge     = Some(Duration.Zero),
      sameSite   = Some(Cookie.SameSite.Lax),
    )

  def parse(content: String, secret: Secret.Bytes32): Either[String, PasskeyRegistrationCookie] =
    CookieSigning.decode(content, secret, ttl.toSeconds).flatMap(_.fromJson[PasskeyRegistrationCookie])
