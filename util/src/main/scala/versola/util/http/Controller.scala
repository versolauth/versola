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
    def isJWT = s.split("\\.").headOption
      .exists(str => Base64Url.decodeStr(str).startsWith("{"))

  given Schema[URL] = Schema.primitive[String].transformOrFail(
    string => URL.decode(string).left.map(_.getMessage),
    url => Right(url.encode)
  )