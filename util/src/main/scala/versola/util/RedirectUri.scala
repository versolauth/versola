package versola.util

import zio.http.{Scheme, URL}
import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema

/**
 * OAuth 2.1 compliant redirect URI
 *
 * Requirements according to RFC 6749 and OAuth 2.1:
 * - Must be an absolute URI
 * - Must NOT contain a fragment component (#)
 * - Should use HTTPS (HTTP allowed only for localhost/127.0.0.1)
 * - Custom schemes are allowed for native applications
 */
type RedirectUri = RedirectUri.Type

object RedirectUri:
  opaque type Type <: String = String

  inline def apply(uri: String): RedirectUri = uri

  def parse(uri: String): Either[String, RedirectUri] =
    URL.decode(uri) match
      case Left(_) =>
        Left(s"Invalid URI format: $uri")
      case Right(url) if !url.isAbsolute =>
        Left("Redirect URI must be absolute")
      case Right(url) if url.fragment.isDefined =>
        Left("Redirect URI must not contain fragment (#)")
      case Right(url) =>
        validateScheme(url, uri)

  private def validateScheme(url: URL, originalUri: String): Either[String, RedirectUri] =
    url.scheme match
      case Some(scheme) if scheme == Scheme.HTTP =>
        url.host match
          case Some(host) if host == "localhost" || host == "127.0.0.1" =>
            Right(RedirectUri(originalUri))
          case _ =>
            Left("HTTP scheme only allowed for localhost or 127.0.0.1")

      case Some(_) =>
        Right(RedirectUri(originalUri))

      case None =>
        Left("Redirect URI must have a scheme")

  given Schema[Type] = Schema.primitive[String]
    .transformOrFail(parse, Right(_))

  given JsonEncoder[Type] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Type] = JsonDecoder.string.mapOrFail(parse)

