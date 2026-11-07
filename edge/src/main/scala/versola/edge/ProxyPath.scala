package versola.edge

import zio.http.Path

import scala.util.matching.Regex

/** The path a proxied call is matched against, read once and refused when edge and the resource could read it differently.
  *
  * A segment of [[Path.segments]] is already percent-decoded, so an encoded separator (`%2F`, `%5C`) arrives as a `/` or `\` inside
  * one segment. Matching it as it stands counts more segments than the resource, which receives the segment re-encoded, so the
  * endpoint permissions were checked for is not the one the resource routes to. The same goes for a dot segment that a resource
  * normalises (RFC 3986 §5.2.4) or decodes once more than edge did, and for a control character a resource may treat as the end of
  * the path. zio-http already collapses plain `.` and `..` segments before a route sees them; this is the check that does not
  * depend on that.
  */
object ProxyPath:

  /** The segments to match, or `None` when any of them is one a resource could take for something else. */
  def segments(path: Path): Option[Vector[String]] =
    val all = path.segments.toVector.filter(_.nonEmpty)
    Option.when(all.forall(safe))(all)

  private def safe(segment: String): Boolean =
    // a resource that decodes the segment once or twice more must not end up on a dot segment or a separator either
    Iterator.iterate(segment)(decode).take(3).forall(plain)

  private def plain(segment: String): Boolean =
    !segment.exists(c => c == '/' || c == '\\' || Character.isISOControl(c)) &&
      // `..;x` is `..` to a resource that drops path parameters (RFC 3986 §3.3) before it normalises
      !Set(".", "..").contains(segment.takeWhile(_ != ';'))

  private val Escape = "%([0-9a-fA-F]{2})".r

  /** One round of percent-decoding of the ASCII octets, which are the only ones the checks above are about; a `%` not followed by two
    * hex digits, and an octet of a multi-byte character, are left as they are. */
  private def decode(value: String): String =
    Escape.replaceAllIn(
      value,
      found =>
        val octet = Integer.parseInt(found.group(1), 16)
        Regex.quoteReplacement(if octet < 0x80 then octet.toChar.toString else found.matched),
    )
