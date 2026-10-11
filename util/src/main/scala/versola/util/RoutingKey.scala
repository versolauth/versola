package versola.util

import zio.Config
import zio.config.magnolia.DeriveConfig

import java.nio.charset.StandardCharsets
import scala.util.Try

/** The cell that issued an opaque value, written at the front of it as `v1.<cell>.` so that
  * a router in front of several cells can send the value to its home cell without a lookup
  * (versolauth/versola#547 §6.2).
  *
  * The prefix is a hint, not a credential: a changed prefix sends the value to a cell that
  * has no record keyed by it and rejects it, so nothing here signs anything. Parsing is still
  * strict -- a value that announces a routing key and then fails to spell one is refused
  * rather than read as unprefixed -- so that a router and a cell never disagree on what a
  * value is.
  *
  * A value issued before cells existed carries no prefix and is read as belonging to the
  * cell that receives it: [[decode]] answers `None` for it, never an error.
  */
object RoutingKey:
  val Version = "v1"

  private val Separator = '.'

  /** Globally unique cell identifier, `kz-1` rather than `1`, so that artifacts of two
    * partitions never collide (versolauth/versola#547 §10). Lowercase ASCII letters, digits
    * and hyphens only: the separator can then never appear inside one, and it reads the same
    * in a URL, a claim and a log line.
    */
  opaque type CellId <: String = String

  object CellId:
    val MaxLength = 64

    private val Shape = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?".r

    def parse(value: String): Either[String, CellId] =
      if value.length > MaxLength then Left(s"cell id is longer than $MaxLength characters")
      else if Shape.matches(value) then Right(value)
      else Left("cell id must be lowercase letters, digits and hyphens, neither starting nor ending with a hyphen")

    /** Read from configuration by the same rule as from a presented value. */
    given DeriveConfig[CellId] = DeriveConfig[String]
      .mapOrFail(parse(_).left.map(message => Config.Error.InvalidData(message = message)))

  /** A presented value split into the cell it names, if any, and the value proper. */
  final case class Decoded(cell: Option[CellId], value: String)

  def encode(cell: CellId, value: String): String =
    s"$Version$Separator$cell$Separator$value"

  /** Strict on anything that starts like a routing key: `v1.` must be followed by a
    * well-formed cell id, the separator and a non-empty value. A value that does not start
    * with `v1.` is one issued without a key.
    */
  def decode(presented: String): Either[String, Decoded] =
    val announced = s"$Version$Separator"
    if !presented.startsWith(announced) then Right(Decoded(None, presented))
    else
      val rest = presented.drop(announced.length)
      rest.indexOf(Separator) match
        case -1 => Left("routing key names no value")
        case at =>
          val value = rest.drop(at + 1)
          if value.isEmpty then Left("routing key names no value")
          else CellId.parse(rest.take(at)).map(cell => Decoded(Some(cell), value))

  /** The value without its routing key, when it had one. */
  def strip(presented: String): String =
    decode(presented).fold(_ => presented, _.value)

  /** The byte forms of an opaque value whose string form is base64url. Under a routing key
    * the bytes a server keys on are the UTF-8 of the whole routed string, so a MAC of them
    * covers the prefix too and a value presented with a different prefix than it was issued
    * with matches nothing. Without one they are the random bytes themselves, which is what
    * a server keyed on before routing keys existed and still has to match for the values it
    * issued then.
    */
  object Bytes:

    /** The bytes to key on for a fresh value drawn as `random`. */
    def issue(cell: Option[CellId], random: Array[Byte]): Array[Byte] =
      cell match
        case None => random
        case Some(cell) => encode(cell, Base64.urlEncode(random)).getBytes(StandardCharsets.UTF_8)

    /** The bytes to key on for a presented value. Both forms are accepted, each read the way
      * it was issued. */
    def parse(presented: String): Either[String, Array[Byte]] =
      decode(presented).flatMap:
        case Decoded(None, raw) => decodeBase64Url(raw)
        case Decoded(Some(_), raw) => decodeBase64Url(raw).map(_ => presented.getBytes(StandardCharsets.UTF_8))

    /** The string a client is given for `bytes`; [[parse]] reads it back to the same bytes.
      * A routed value's bytes are the ASCII of its own string, so they decode as one; random
      * bytes are presented as base64url. The check that the text re-encodes to exactly
      * `bytes` keeps the two a bijection: a value is presented as routed only when parsing
      * that presentation yields the bytes it was presented from.
      */
    def present(bytes: Array[Byte]): String =
      val asText = new String(bytes, StandardCharsets.US_ASCII)
      val routed = decode(asText).exists(_.cell.isDefined) && parse(asText).exists(_.sameElements(bytes))
      if routed then asText else Base64.urlEncode(bytes)

    private def decodeBase64Url(raw: String): Either[String, Array[Byte]] =
      if raw.isEmpty then Left("value is empty")
      else Try(Base64.urlDecode(raw)).toEither.left.map(_.getMessage)
