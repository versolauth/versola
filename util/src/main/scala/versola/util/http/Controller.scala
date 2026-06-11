package versola.util.http

import versola.util.{Base64Url, FormDecoder, Secret}
import zio.{IO, ZIO}
import zio.http.*
import zio.schema.Schema

trait Controller:
  type Env >: Nothing
  type Tracing = zio.telemetry.opentelemetry.tracing.Tracing

  def routes: Routes[Env, Throwable]

  extension (request: Request)
    def formAs[A: FormDecoder as decoder]: IO[String, A] =
      request.body.asURLEncodedForm.mapError(_.getMessage)
        .flatMap(decoder.decode)

  extension (s: String)
    def isJWT = s.split("\\.").headOption
      .exists(str => Base64Url.decodeStr(str).startsWith("{"))

  given Schema[URL] = Schema.primitive[String].transformOrFail(
    string => URL.decode(string).left.map(_.getMessage),
    url => Right(url.encode)
  )