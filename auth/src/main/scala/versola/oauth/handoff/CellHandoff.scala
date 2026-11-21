package versola.oauth.handoff

import org.apache.commons.codec.digest.Blake3
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.conversation.model.ConversationRecord
import versola.util.{Base64, CoreConfig, Dpop, Email, Phone, RoutingKey, SecurityService}
import zio.http.{Path, URL}
import zio.json.*
import zio.{Clock, Duration, IO, Task, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets
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
  */
trait CellHandoff:
  /** The value of the `handoff` parameter: the routing key of `to` in front of the seal, so that what is in front of the
    * cells sends the browser there. */
  def seal(record: ConversationRecord, credential: Either[Email, Phone], to: RoutingKey.CellId): Task[String]

  /** What was sealed for this cell and has not expired. */
  def open(presented: String): IO[CellHandoff.Refused, CellHandoff.Payload]

object CellHandoff:
  val Lifetime: Duration = 1.minute

  /** The parameter of `/challenge/handoff`. */
  val Parameter = "handoff"

  /** Why a handoff is not taken; a closed set, it is the description of the recorded error. */
  enum Refused(val reason: String):
    case Malformed extends Refused("malformed")
    case Forged extends Refused("not sealed by a cell")
    case Expired extends Refused("expired")
    case Elsewhere extends Refused("sealed for another cell")
    case NotACell extends Refused("this auth is not a cell")

  /** What an authorization request asked for, as the parameters of `/authorize` it was made with or resolved to, with
    * the address typed as its `login_hint`. The home cell validates them as it validates any authorization request.
    *
    * @param to the cell the seal is for
    * @param expiresAt epoch seconds
    */
  final case class Payload(params: Map[String, List[String]], to: String, expiresAt: Long) derives JsonCodec

  /** The parameters of the request a conversation was started for, with the credential as the login hint. A pushed
    * request and a request object are already resolved in the record, so what is written here is the request itself. */
  private[handoff] def paramsOf(record: ConversationRecord, credential: Either[Email, Phone]): Map[String, List[String]] =
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
      one("login_hint", Some(credential.merge)),
      Option.when(record.resources.nonEmpty)("resource" -> record.resources.map(identity[String])),
    ).flatten.toMap

  /** Never to change: it separates this key from every other use of the secret it is derived from. */
  private val Context = "versola auth cell handoff v1".getBytes(StandardCharsets.UTF_8)

  val live: ZLayer[CoreConfig & SecurityService, Nothing, CellHandoff] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(config: CoreConfig, security: SecurityService) extends CellHandoff:
    private val key: SecretKeySpec =
      val derived = Array.ofDim[Byte](32)
      Blake3.initKeyDerivationFunction(Context).update(config.central.secretKey.getEncoded).doFinalize(derived)
      SecretKeySpec(derived, "AES")

    override def seal(record: ConversationRecord, credential: Either[Email, Phone], to: RoutingKey.CellId): Task[String] =
      for
        now <- Clock.instant
        payload = Payload(params = paramsOf(record, credential), to = to, expiresAt = now.plus(Lifetime).getEpochSecond)
        ciphertext <- security.encryptAes256(payload.toJson.getBytes(StandardCharsets.UTF_8), key)
      yield RoutingKey.encode(to, Base64.urlEncode(ciphertext))

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
