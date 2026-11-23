package versola.oauth.handoff

import org.apache.commons.codec.digest.Blake3
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.conversation.model.ConversationRecord
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.SupersededSession
import versola.util.{Base64, CoreConfig, Dpop, Email, Phone, RoutingKey, SecureRandom, SecurityService}
import zio.http.{Path, URL}
import zio.json.*
import zio.{Clock, Duration, IO, Task, UIO, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

/** A sign-in handed from the cell that took it to the cell that holds its user (versolauth/versola#547 D18, #1046).
  *
  * A new sign-in is placed in a cell before anyone knows who is signing in. When the address typed belongs to a user of
  * another cell, the first cell seals what the authorization request asked for together with the address, and sends
  * the browser to the home cell with it; the home cell opens it and starts the request as its own `/authorize` would,
  * with the address as the login hint.
  *
  * The seal is AES-GCM under a key derived from the key every `auth` shares with central, so only a cell makes or reads
  * one and the address is not readable in the URL. It names the cell it is for and lives [[CellHandoff.Lifetime]]. It
  * grants nothing: opening it starts a sign-in for that address, which is what typing the address does, so it is not
  * kept to be refused a second time.
  *
  * ==A submission handed over (versolauth/versola#1048)==
  * A login and password, or a passkey with no address typed, name the user only once they are submitted, and the person
  * is not asked for them twice: the first cell answers the POST with a `307` to the home cell, the browser repeats the
  * same POST there, and the home cell checks it in the request it starts. Such a seal does more than start a sign-in,
  * so it carries more: an id the home cell takes once, a digest of the first cell's conversation cookie, which the
  * browser must still present (a seal made for somebody's own account cannot be posted from another site into a
  * browser that never held that conversation), and, for a passkey, the request the assertion answers.
  */
trait CellHandoff:
  /** The value of the `handoff` parameter: the routing key of `to` in front of the seal, so that what is in front of the
    * cells sends the browser there. */
  def seal(record: ConversationRecord, credential: Either[Email, Phone], to: RoutingKey.CellId): Task[String]

  /** The same for a submission the home cell is to check ([[CellHandoff.Submission]]).
    *
    * @param cookie the content of this cell's conversation cookie, as the browser presented it
    * @param passkeyRequest the assertion request a passkey submission answers; none for a login and password
    */
  def sealSubmission(record: ConversationRecord, to: RoutingKey.CellId, cookie: String, passkeyRequest: Option[String]): Task[String]

  /** What was sealed for this cell and has not expired. */
  def open(presented: String): IO[CellHandoff.Refused, CellHandoff.Payload]

object CellHandoff:
  val Lifetime: Duration = 1.minute

  /** The most a seal may be, in the characters of its parameter. It travels in a URL, and what is in front of the cells
    * refuses a request line or a `Location` header past a few kilobytes (8 KiB is the usual default); an authorization
    * request with a very large `authorization_details` or `claims` would otherwise be lost on the way, after the first
    * cell had ended its conversation. */
  val MaxLength = 6000

  /** A request too large to be sealed into a URL ([[MaxLength]]). The caller keeps its conversation. */
  final case class TooLarge(length: Int) extends RuntimeException(s"a handoff of $length characters is over $MaxLength")

  /** The parameter of `/challenge/handoff`. */
  val Parameter = "handoff"

  /** Why a handoff is not taken; a closed set, it is the description of the recorded error. */
  enum Refused(val reason: String):
    case Malformed extends Refused("malformed")
    case Forged extends Refused("not sealed by a cell")
    case Expired extends Refused("expired")
    case Elsewhere extends Refused("sealed for another cell")
    case NotACell extends Refused("this auth is not a cell")
    case NotASubmission extends Refused("sealed to start a sign-in, not to carry a submission")
    case Submission extends Refused("sealed to carry a submission, which a GET does not have")
    case AnotherBrowser extends Refused("not the browser the sign-in was started in")
    case Replayed extends Refused("presented before")

  /** What an authorization request asked for, as the parameters of `/authorize` it was made with or resolved to, with
    * the address typed as its `login_hint`. The home cell validates them as it validates any authorization request.
    *
    * @param to the cell the seal is for
    * @param expiresAt epoch seconds
    * @param submission set when the request that follows the seal is the submission the first cell took
    * @param superseded the session the browser had in the first cell when the sign-in was started there, which a
    *   sign-in that finishes in the home cell replaces: the home cell has it ended through central once the sign-in is
    *   finished, as the first cell would have ended it itself
    */
  final case class Payload(
      params: Map[String, List[String]],
      to: String,
      expiresAt: Long,
      submission: Option[Submission],
      superseded: Option[SupersededSession],
  ) derives JsonCodec

  /** What a seal that hands a submission over carries besides the request.
    *
    * @param id taken once by the home cell: the submission is a credential, and a passkey assertion must not be good twice
    * @param cookie [[cookieDigest]] of the first cell's conversation cookie
    * @param passkeyRequest the assertion request (with its challenge) the passkey answered; none for a login and password
    */
  final case class Submission(id: String, cookie: String, passkeyRequest: Option[String]) derives JsonCodec

  /** What a seal keeps of a conversation cookie. */
  def cookieDigest(content: String): String =
    Base64.urlEncode(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)))

  /** Whether `content` is the cookie a seal was made with. */
  def sameCookie(submission: Submission, content: String): Boolean =
    MessageDigest.isEqual(
      submission.cookie.getBytes(StandardCharsets.UTF_8),
      cookieDigest(content).getBytes(StandardCharsets.UTF_8),
    )

  /** The parameters of the request a conversation was started for, with the credential, where there is one, as the login
    * hint. A pushed request and a request object are already resolved in the record, so what is written here is the
    * request itself.
    *
    * What decides whether a session the browser already has may be used is not written, because the home cell has no
    * such session to decide about: a session cookie names its cell, so a browser with a session in the home cell was
    * sent there by `/authorize` in the first place. That is `max_age`, `prompt=login` and `prompt=select_account`; the
    * sign-in in the home cell is always a new authentication, and its `auth_time` says so. `prompt=none` never starts
    * a conversation, and an `id_token_hint` that names a user starts one that knows its user, which is not handed over.
    *
    * The session the browser had in the first cell is named in the seal beside the parameters
    * ([[Payload.superseded]]): a sign-in that finishes in the home cell has it ended through central. Its refresh
    * tokens are ended with it and not moved to the new session, as they are for the same user in one cell: the user of
    * a session in this cell is not a user of the home cell. */
  private[handoff] def paramsOf(record: ConversationRecord, credential: Option[Either[Email, Phone]]): Map[String, List[String]] =
    def one(name: String, value: Option[String]) = value.map(name -> List(_))
    List(
      one("client_id", Some(record.clientId)),
      one("redirect_uri", Some(record.redirectUri.encode)),
      one("response_type", Some(if record.responseType.contains(ResponseTypeEntry.IdToken) then "code id_token" else "code")),
      one("response_mode", Some(record.responseMode.parameterValue)),
      one("scope", Option.when(record.scope.nonEmpty)(record.scope.toList.map(identity[String]).sorted.mkString(" "))),
      one("state", record.state.map(identity[String])),
      one("code_challenge", record.codeChallenge.map(identity[String])),
      one("code_challenge_method", record.codeChallengeMethod.map(_.toString)),
      one("nonce", record.nonce.map(identity[String])),
      one("claims", record.requestedClaims.map(_.toJson)),
      one("ui_locales", record.uiLocales.filter(_.nonEmpty).map(_.mkString(" "))),
      one("prompt", Option.when(record.promptConsent)("consent")),
      one("acr_values", record.targetAcr.map(identity[String])),
      one("authorization_details", record.authorizationDetails.map(_.toJson)),
      one(Dpop.Jkt.Parameter, record.dpopJkt),
      one("login_hint", credential.map(_.merge)),
      Option.when(record.resources.nonEmpty)("resource" -> record.resources.map(identity[String])),
    ).flatten.toMap

  /** Never to change: it separates this key from every other use of the secret it is derived from. */
  private val Context = "versola auth cell handoff v1".getBytes(StandardCharsets.UTF_8)

  val live: ZLayer[CoreConfig & SecurityService & SecureRandom & SessionRepository, Nothing, CellHandoff] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(config: CoreConfig, security: SecurityService, random: SecureRandom, sessions: SessionRepository) extends CellHandoff:
    private val key: SecretKeySpec =
      val derived = Array.ofDim[Byte](32)
      Blake3.initKeyDerivationFunction(Context).update(config.central.secretKey.getEncoded).doFinalize(derived)
      SecretKeySpec(derived, "AES")

    override def seal(record: ConversationRecord, credential: Either[Email, Phone], to: RoutingKey.CellId): Task[String] =
      sealOf(record, params = paramsOf(record, Some(credential)), to = to, submission = None)

    override def sealSubmission(record: ConversationRecord, to: RoutingKey.CellId, cookie: String, passkeyRequest: Option[String]): Task[String] =
      random.nextHex(32).flatMap: id =>
        sealOf(
          record,
          params = paramsOf(record, None),
          to = to,
          submission = Some(Submission(id = id, cookie = cookieDigest(cookie), passkeyRequest = passkeyRequest)),
        )

    /** The session this sign-in replaces, as the home cell can name it: the one handed here with the sign-in, or this
      * cell's own, by its public id. A session that cannot be read is left out, and stays until it expires: the
      * sign-in is not refused for it. */
    private def supersededBy(record: ConversationRecord): UIO[Option[SupersededSession]] =
      record.supersededSession match
        case handed @ Some(_) => ZIO.succeed(handed)
        case None =>
          (record.priorSessionId, record.priorSessionUserId) match
            case (Some(id), Some(userId)) =>
              sessions.findSession(id)
                .map(_.map(session => SupersededSession(userId = userId, sessionId = session.publicId)))
                .catchAllCause(cause =>
                  ZIO.logWarningCause("Could not read the session a handed sign-in replaces; it is left to expire", cause).as(None),
                )
            case _ => ZIO.none

    private def sealOf(
        record: ConversationRecord,
        params: Map[String, List[String]],
        to: RoutingKey.CellId,
        submission: Option[Submission],
    ): Task[String] =
      for
        now <- Clock.instant
        superseded <- supersededBy(record)
        payload = Payload(params = params, to = to, expiresAt = now.plus(Lifetime).getEpochSecond, submission = submission, superseded = superseded)
        ciphertext <- security.encryptAes256(payload.toJson.getBytes(StandardCharsets.UTF_8), key)
        ticket = RoutingKey.encode(to, Base64.urlEncode(ciphertext))
        _ <- ZIO.fail(TooLarge(ticket.length)).when(ticket.length > MaxLength)
      yield ticket

    override def open(presented: String): IO[Refused, Payload] =
      for
        own <- ZIO.fromOption(config.cellId).orElseFail(Refused.NotACell)
        decoded <- ZIO.fromEither(RoutingKey.decode(presented)).orElseFail(Refused.Malformed)
        bytes <- ZIO.attempt(Base64.urlDecode(decoded.value)).orElseFail(Refused.Malformed)
        opened <- security.decryptAes256(bytes, key).orElseFail(Refused.Forged)
        payload <- ZIO.fromEither(String(opened, StandardCharsets.UTF_8).fromJson[Payload]).orElseFail(Refused.Malformed)
        // The cell is inside the seal: the key in front of it only chose where the browser was sent.
        _ <- ZIO.fail(Refused.Elsewhere).unless(payload.to == (own: String))
        now <- Clock.instant
        _ <- ZIO.fail(Refused.Expired).when(now.getEpochSecond >= payload.expiresAt)
      yield payload

  /** Where the first cell sends the browser. */
  def location(ticket: String): URL =
    URL.root.copy(path = Path.root / "challenge" / "handoff").addQueryParam(Parameter, ticket)
