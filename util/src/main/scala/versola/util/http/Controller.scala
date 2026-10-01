package versola.util.http

import versola.util.{Base64Url, FormDecoder, Secret}
import zio.{IO, ZIO}
import zio.http.*
import zio.json.JsonDecoder
import zio.schema.Schema

trait Controller:
  type Env >: Nothing
  type Tracing = zio.telemetry.opentelemetry.tracing.Tracing

  def routes: Routes[Env, Throwable]

  extension (request: Request)
    def formAs[A: FormDecoder as decoder]: IO[String, A] =
      request.body.asURLEncodedForm.mapError(_.getMessage)
        .flatMap(decoder.decode)

    /** Decodes the JSON body as `A`, failing with [[BadRequest]] (rather than the generic
      * `RuntimeException` `asJsonFromCodec` raises) so a malformed body or an invalid field -
      * e.g. an ID newtype's `mapOrFail` rejecting the value - surfaces as 400, not 500. */
    def bodyAs[A: JsonDecoder]: IO[BadRequest, A] =
      request.body.asJsonFromCodec[A].mapError(e => BadRequest(e.getMessage))

  extension (s: String)
    /** A JWT is three dot-separated segments (five for a JWE), the first a base64url-encoded JSON
      * header. The segment count comes first: an opaque base64url token has no dot, and one in
      * 256 random ones decodes to a leading `{`. A value that does not decode at all is simply
      * not a JWT, so this answers `false` rather than letting the decoder's
      * `IllegalArgumentException` escape - callers use it inside plain expressions (e.g. a
      * `FormDecoder` parse function) where there is no error channel to catch it. */
    def isJWT =
      val segments = s.split("\\.", -1)
      (segments.length == 3 || segments.length == 5) &&
        scala.util.Try(Base64Url.decodeStr(segments.head)).toOption.exists(_.startsWith("{"))

  given Schema[URL] = Schema.primitive[String].transformOrFail(
    string => URL.decode(string).left.map(_.getMessage),
    url => Right(url.encode)
  )