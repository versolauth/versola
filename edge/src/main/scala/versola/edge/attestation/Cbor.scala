package versola.edge.attestation

import java.nio.charset.StandardCharsets

/** The part of CBOR (RFC 8949) an App Attest attestation object and assertion are made of: unsigned and negative integers, byte and
  * text strings, arrays and maps, of definite length. Indefinite lengths, tags and floats are refused -- none occurs in what Apple
  * produces, and a decoder that accepts less is less to get wrong. Input is bounded by the caller; nesting and the number of items
  * are bounded here, since this reads what a client chose to send. */
enum Cbor derives CanEqual:
  case Unsigned(value: Long)
  case Negative(value: Long)
  case Bytes(value: Array[Byte])
  case Text(value: String)
  case Arr(items: Vector[Cbor])
  case Mapping(entries: Vector[(Cbor, Cbor)])

  /** The value of a text key of a map. */
  def get(key: String): Option[Cbor] = this match
    case Cbor.Mapping(entries) => entries.collectFirst { case (Cbor.Text(`key`), value) => value }
    case _ => None

  def bytes: Option[Array[Byte]] = this match
    case Cbor.Bytes(value) => Some(value)
    case _ => None

  def text: Option[String] = this match
    case Cbor.Text(value) => Some(value)
    case _ => None

object Cbor:
  private val MaxDepth = 8
  private val MaxItems = 256

  /** The whole of `input` as one item, or why it is not. */
  def decode(input: Array[Byte]): Either[String, Cbor] =
    val reader = Reader(input)
    try
      val item = reader.item(depth = 0)
      Either.cond(reader.atEnd, item, "trailing bytes after the CBOR item")
    catch case failure: Failure => Left(failure.getMessage)

  private final class Failure(message: String) extends RuntimeException(message, null, false, false)

  private final class Reader(input: Array[Byte]):
    private var position = 0
    private var items = 0

    def atEnd: Boolean = position == input.length

    private def fail(message: String): Nothing = throw Failure(message)

    private def take(count: Long): Array[Byte] =
      if count < 0 || count > input.length - position then fail("CBOR item is longer than its input")
      val chunk = java.util.Arrays.copyOfRange(input, position, position + count.toInt)
      position += count.toInt
      chunk

    private def byte(): Int = take(1)(0) & 0xff

    /** The argument that follows a major type: the additional information, or the bytes it says follow. */
    private def argument(info: Int): Long = info match
      case small if small < 24 => small.toLong
      case 24 => byte().toLong
      case 25 => (byte() << 8 | byte()).toLong
      case 26 => (0 until 4).foldLeft(0L)((acc, _) => acc << 8 | byte().toLong)
      case 27 =>
        val value = (0 until 8).foldLeft(0L)((acc, _) => acc << 8 | byte().toLong)
        if value < 0 then fail("CBOR integer is larger than this decoder reads")
        value
      case _ => fail("CBOR indefinite lengths and reserved encodings are not accepted")

    def item(depth: Int): Cbor =
      if depth > MaxDepth then fail("CBOR is nested too deeply")
      items += 1
      if items > MaxItems then fail("CBOR holds too many items")
      val head = byte()
      val major = head >> 5
      val value = argument(head & 0x1f)
      major match
        case 0 => Cbor.Unsigned(value)
        case 1 => Cbor.Negative(value)
        case 2 => Cbor.Bytes(take(value))
        case 3 => Cbor.Text(String(take(value), StandardCharsets.UTF_8))
        case 4 =>
          if value > MaxItems then fail("CBOR array is too long")
          Cbor.Arr(Vector.fill(value.toInt)(item(depth + 1)))
        case 5 =>
          if value > MaxItems then fail("CBOR map is too long")
          Cbor.Mapping(Vector.fill(value.toInt)(item(depth + 1) -> item(depth + 1)))
        case _ => fail("CBOR tags and simple values are not accepted")
