package versola.oauth.client.model

import zio.http.URL
import zio.json.{JsonDecoder, JsonEncoder}
import zio.prelude.Equal

/** A resource identifier as used by RFC 8707 (`resource` parameter / `aud` claim):
  * an absolute URI without a fragment. Mirrors `versola.central.configuration.ResourceUri`
  * (auth cannot depend on central, so the identical validation is duplicated here to keep
  * the two services agreeing on what counts as a valid, comparable resource identifier).
  */
type ResourceUri = ResourceUri.Type

object ResourceUri:
  opaque type Type <: String = String

  inline def apply(uri: String): ResourceUri = uri

  def parse(uri: String): Either[String, ResourceUri] =
    URL.decode(uri) match
      case Left(_) =>
        Left(s"Invalid URI format: $uri")
      case Right(url) if !url.isAbsolute =>
        Left("Resource URI must be absolute")
      case Right(url) if url.path.nonEmpty =>
        Left("Resource URI path must be empty")
      case Right(url) if url.queryParams.nonEmpty =>
        Left("Resource URI query must be empty")
      case Right(url) if url.fragment.isDefined =>
        Left("Resource URI fragment must be empty")
      case Right(_) =>
        Right(ResourceUri(uri))

  given Equal[Type] = Equal.make(_ == _)
  given JsonEncoder[Type] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Type] = JsonDecoder.string.mapOrFail(parse)
