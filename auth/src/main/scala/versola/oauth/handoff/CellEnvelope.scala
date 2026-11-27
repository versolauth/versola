package versola.oauth.handoff

import versola.util.{Base64, CoreConfig, JWT, RoutingKey, Rs256Jwt, SecurityService}
import zio.json.*
import zio.json.ast.Json
import zio.{Clock, Duration, IO, Task, ZIO, ZLayer, durationInt}

import java.nio.charset.StandardCharsets

/** What one cell says to another, as bytes only that cell can read and only a cell the reader knows can have made
  * (versolauth/versola#1050, #1051).
  *
  * The cell that seals signs a JWS with its own key (RS256) and encrypts it to the key of the cell it is for
  * ([[SecurityService.encryptRsaHybrid]]); the cell that opens decrypts it with its own private key, finds the sealing cell's
  * keys by the issuer the JWS names, and verifies. No key is shared between cells, so a cell cannot make an envelope in
  * another's name or open one meant for another. The two things that use it, a sign-in handed to another cell
  * ([[CellHandoff]]) and a user moved to another cell ([[versola.user.move.UserMoves]]), put their own content in it.
  *
  * What an envelope is for is inside it ([[Purpose]]), signed with the rest, and checked by whoever opens it: an envelope
  * made to carry a sign-in is refused where a user is expected, and the other way round, whoever made it.
  *
  * An envelope has no browser binding and no id of its own that is taken once: what makes it safe to act on is up to what it
  * is for. A handoff adds both ([[CellHandoff.Submission]]); an exported user is acted on only on the word of central, whose
  * token authorises the call that carries it.
  */
trait CellEnvelope:
  /** `bytes` for `to`, for the use `purpose` names ([[CellEnvelope.Purpose]]): the routing key of `to` in front of the
    * ciphertext, so that what is in front of the cells sends it there. Fails with [[CellEnvelope.NoKey]] when `to` has no key
    * to seal for, and with [[CellEnvelope.NotACell]] when this auth is no cell and so has no key to sign with. */
  def seal(to: RoutingKey.CellId, purpose: String, bytes: Array[Byte]): Task[String] =
    sealMade(to, purpose, ZIO.succeed(bytes))

  /** The same for content that is made only once it is known that `to` can be sealed for: nothing is read or computed for a
    * cell that has no key. */
  def sealMade(to: RoutingKey.CellId, purpose: String, content: Task[Array[Byte]]): Task[String]

  /** The cell that made `presented` and what it sealed in it, when it was sealed for this cell, for `purpose`, and has not
    * expired. */
  def open(purpose: String, presented: String): IO[CellEnvelope.Refused, (String, Array[Byte])]

object CellEnvelope:
  /** What an envelope is for. Closed: a purpose that is not here is never sealed. */
  object Purpose:
    val Handoff = "handoff"
    val UserMove = "user-move"

    /** How long an envelope of this purpose may be opened. A handoff is the browser's next request; a moved user is carried
      * by central across its retries of a step, so it lives longer. */
    def lifetimeOf(purpose: String): Option[Duration] = purpose match
      case Handoff => Some(1.minute)
      case UserMove => Some(10.minutes)
      case _ => None

  /** A cell this auth has no key for (not listed by central, or listed with none): nothing can be sealed for it. */
  final case class NoKey(cell: String) extends RuntimeException(s"cell '$cell' has no key to seal for")

  /** An auth that is no cell has no key of its own, and so no envelope to make. */
  case object NotACell extends RuntimeException("an auth that is no cell has no envelope to seal")

  /** Why an envelope is not opened; a closed set, `reason` is the description of the recorded error. */
  enum Refused(val reason: String, val code: String):
    case Malformed extends Refused("malformed", "malformed")
    case Forged extends Refused("not sealed by a cell", "forged")
    case Expired extends Refused("expired", "expired")
    case Elsewhere extends Refused("sealed for another cell", "elsewhere")
    case NotACell extends Refused("this auth is not a cell", "not_a_cell")
    case WrongPurpose extends Refused("sealed for another use", "purpose")

  val live: ZLayer[CoreConfig & SecurityService & CellRegistry, Nothing, CellEnvelope] =
    ZLayer.fromFunction(Impl(_, _, _))

  /** The claims of an envelope that matter to the cell that opens it. */
  private case class Claims(iss: String, aud: List[String], purpose: String, body: String)

  private object Claims:
    given JsonDecoder[Claims] = JsonDecoder[Json.Obj].mapOrFail: obj =>
      for
        iss <- obj.get("iss").flatMap(_.asString).toRight("no iss")
        aud <- obj.get("aud") match
          case Some(Json.Str(single)) => Right(List(single))
          case Some(Json.Arr(many)) => Right(many.collect { case Json.Str(value) => value }.toList)
          case _ => Left("no aud")
        purpose <- obj.get("purpose").flatMap(_.asString).toRight("no purpose")
        body <- obj.get("body").flatMap(_.asString).toRight("no body")
      yield Claims(iss, aud, purpose, body)

  /** Only the issuer, read before the signature is, to find the key it is verified with. */
  private case class Issuer(iss: String) derives JsonDecoder

  class Impl(config: CoreConfig, security: SecurityService, registry: CellRegistry) extends CellEnvelope:

    override def sealMade(to: RoutingKey.CellId, purpose: String, content: Task[Array[Byte]]): Task[String] =
      for
        own <- ZIO.fromOption(config.cellId).orElseFail(CellEnvelope.NotACell)
        lifetime <-
          ZIO.fromOption(Purpose.lifetimeOf(purpose)).orElseFail(IllegalArgumentException("an envelope of an unknown purpose is not sealed"))
        // Before anything else is made: a cell with no key is not sealed for.
        recipient <- registry.encryptionKeyOf(to).some
          // A cell registered a moment ago: read the cells once more before it is refused.
          .orElse(registry.refreshAfterMiss *> registry.encryptionKeyOf(to).some)
          .orElseFail(NoKey(to))
        bytes <- content
        jws <- JWT.serialize(
          claims = JWT.Claims(
            issuer = own,
            subject = own,
            audience = List(to),
            custom = Json.Obj("purpose" -> Json.Str(purpose), "body" -> Json.Str(Base64.urlEncode(bytes))),
          ),
          ttl = lifetime,
          signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, config.central.keyId, config.central.privateKey),
        )
        ciphertext <- security.encryptRsaHybrid(jws.getBytes(StandardCharsets.UTF_8), recipient)
      yield RoutingKey.encode(to, Base64.urlEncode(ciphertext))

    /** Everything that is not the cell's own key, the sealing cell's, the audience, the purpose, or the clock is an envelope
      * that was not made by a cell this one knows. */
    override def open(purpose: String, presented: String): IO[Refused, (String, Array[Byte])] =
      for
        own <- ZIO.fromOption(config.cellId).orElseFail(Refused.NotACell)
        decoded <- ZIO.fromEither(RoutingKey.decode(presented)).orElseFail(Refused.Malformed)
        bytes <- ZIO.attempt(Base64.urlDecode(decoded.value)).orElseFail(Refused.Malformed)
        opened <- security.decryptRsaHybrid(bytes, config.central.privateKey).orElseFail(Refused.Forged)
        jws = String(opened, StandardCharsets.UTF_8)
        // Read before it is verified, only to know whose key to verify it with: nothing else in it is believed until then.
        sealer <- JWT.parseClaims[Issuer](jws).orElseFail(Refused.Forged)
        keys <- registry.keysOf(sealer.iss).some
          .orElse(registry.refreshAfterMiss *> registry.keysOf(sealer.iss).some)
          .orElseFail(Refused.Forged)
        claims <- Rs256Jwt.deserialize[Claims](jws, keys).mapError:
          case JWT.Error.Expired(_) => Refused.Expired
          case _ => Refused.Forged
        // The cell is inside the envelope: the key in front of it only chose where it was sent.
        _ <- ZIO.fail(Refused.Elsewhere).unless(claims.aud.contains(own: String))
        // What it was made for is signed with the rest, so it cannot be changed on the way; and it is compared after the
        // signature, so that nothing about an envelope that is not one is learned from which use it was for.
        _ <- ZIO.fail(Refused.WrongPurpose).unless(claims.purpose == purpose)
        content <- ZIO.attempt(Base64.urlDecode(claims.body)).orElseFail(Refused.Forged)
      yield (claims.iss, content)
