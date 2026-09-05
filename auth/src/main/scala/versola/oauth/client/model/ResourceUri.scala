package versola.oauth.client.model

import zio.http.URL
import zio.json.{JsonDecoder, JsonEncoder}
import zio.prelude.Equal

/** A resource identifier as used by RFC 8707 (`resource` parameter / `aud` claim):
  * an absolute URI without a query or fragment. Internal resources use the
  * `resource://{resourceId}` form.
  */
type ResourceUri = ResourceUri.Type

object ResourceUri:
  opaque type Type <: String = String

  private val FormValueSeparator = ",(?=[A-Za-z][A-Za-z0-9+.-]*:)"

  inline def apply(uri: String): ResourceUri = uri

  /** zio-http joins repeated URL-encoded form fields with commas. Split only before another
    * absolute-URI scheme so commas that belong to a resource URI remain intact. */
  def splitFormValue(value: String): List[String] =
    value.split(FormValueSeparator).toList

  def internalResourceId(uri: ResourceUri): Option[ResourceId] =
    val prefix = "resource://"
    Option.when(uri.startsWith(prefix))(uri.stripPrefix(prefix))
      .filter(id => id.nonEmpty && !id.contains('/'))
      .map(ResourceId(_))

  def parse(uri: String): Either[String, ResourceUri] =
    URL.decode(uri) match
      case Left(_) =>
        Left(s"Invalid URI format: $uri")
      case Right(url) if !url.isAbsolute =>
        Left("Resource URI must be absolute")
      case Right(url) if url.queryParams.nonEmpty =>
        Left("Resource URI query must be empty")
      case Right(url) if url.fragment.isDefined =>
        Left("Resource URI fragment must be empty")
      case Right(_) =>
        Right(ResourceUri(uri))

  given Equal[Type] = Equal.make(_ == _)
  given JsonEncoder[Type] = JsonEncoder.string.contramap(identity)
  given JsonDecoder[Type] = JsonDecoder.string.mapOrFail(parse)
