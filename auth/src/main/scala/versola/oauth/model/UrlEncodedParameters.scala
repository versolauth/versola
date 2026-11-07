package versola.oauth.model

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.util.Try

/** The parameters of an `application/x-www-form-urlencoded` body, one entry per occurrence and in order.
  *
  * zio-http's own form parsing joins a repeated field into one comma-separated value, so a parameter sent twice cannot be told
  * from one sent once, and RFC 6749 §3.1 / §3.2 (request parameters MUST NOT be included more than once) cannot be enforced on
  * what it returns. This reads the body as sent.
  */
object UrlEncodedParameters:

  /** `None` when a name or value is not valid percent-encoding. `+` is a space, as in a form. */
  def parse(body: String): Option[List[(String, String)]] =
    Try:
      body.split('&').iterator.filter(_.nonEmpty).map { pair =>
        val (name, value) = pair.indexOf('=') match
          case -1 => (pair, "")
          case at => (pair.substring(0, at), pair.substring(at + 1))
        URLDecoder.decode(name, StandardCharsets.UTF_8) -> URLDecoder.decode(value, StandardCharsets.UTF_8)
      }.toList
    .toOption

  /** The names that occur more than once. */
  def repeated(parameters: List[(String, String)]): Set[String] =
    parameters.groupBy(_._1).collect { case (name, occurrences) if occurrences.sizeIs > 1 => name }.toSet
