package versola.oauth.account

import org.apache.commons.codec.digest.Blake3
import versola.user.model.UserId
import versola.util.{Base64, Secret}
import zio.json.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** The in-progress WebAuthn enrollment ceremony of the account page, held by the browser
  * between `register/start` and `register/finish`.
  *
  * The conversation flow keeps this state on its [[versola.oauth.conversation.model.ConversationRecord]],
  * and other browser-held state travels in a cookie. Neither works here: the account page is
  * reached through the edge proxy, which strips `Set-Cookie` from upstream responses, and the
  * page runs outside any conversation. So the state travels through the client instead,
  * authenticated the same way [[versola.oauth.model.ConversationCookie]] authenticates its
  * payload - `base64url(json).base64url(blake3-keyed-mac)` - which makes it unforgeable
  * without being secret: `request` only carries the challenge and relying-party data the
  * browser is handed anyway in `publicKeyOptions`.
  *
  * `userId` binds the ceremony to the user who started it, and is compared against the
  * subject of the access token on submission, so a ticket cannot be replayed by another
  * user. `expiresAt` bounds how long an unfinished ceremony stays usable.
  */
case class PasskeyEnrollmentTicket(
    userId: UserId,
    expiresAt: Instant,
    request: String,
) derives JsonCodec

object PasskeyEnrollmentTicket:
  /** Domain separation tag: keeps a ticket from being accepted anywhere else that signs
    * with the same key, and vice versa. */
  private val Context = "passkey-enroll"

  def serialize(ticket: PasskeyEnrollmentTicket, secret: Secret.Bytes32): String =
    val payload = ticket.toJson.getBytes(StandardCharsets.UTF_8)
    s"${Base64.urlEncode(payload)}.${Base64.urlEncode(mac(payload, secret))}"

  /** Parses and verifies a ticket produced by [[serialize]]. Returns [[Left]] if the
    * content is malformed, the signature does not match, or the ceremony has expired. */
  def parse(content: String, secret: Secret.Bytes32, now: Instant): Either[String, PasskeyEnrollmentTicket] =
    val dotIdx = content.lastIndexOf('.')
    if dotIdx < 0 then Left("missing signature")
    else
      for
        payload <- scala.util.Try(Base64.urlDecode(content.substring(0, dotIdx))).toEither.left.map(_.getMessage)
        signature <- scala.util.Try(Base64.urlDecode(content.substring(dotIdx + 1))).toEither.left.map(_.getMessage)
        _ <- Either.cond(MessageDigest.isEqual(mac(payload, secret), signature), (), "invalid signature")
        ticket <- new String(payload, StandardCharsets.UTF_8).fromJson[PasskeyEnrollmentTicket]
        _ <- Either.cond(ticket.expiresAt.isAfter(now), (), "ceremony expired")
      yield ticket

  private def mac(data: Array[Byte], key: Secret.Bytes32): Array[Byte] =
    val out = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(key)
      .update(Context.getBytes(StandardCharsets.UTF_8))
      .update(data)
      .doFinalize(out)
    out
