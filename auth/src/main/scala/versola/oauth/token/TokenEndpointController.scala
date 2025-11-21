package versola.oauth.token

import zio.Task
import zio.http.{Method, Request, Response, Routes, handler}
import zio.telemetry.opentelemetry.tracing.Tracing

object TokenEndpointController:
  type Env = Tracing

  def routes: Routes[Env, Nothing] = Routes(
    tokenEndpoint
  )

  val tokenEndpoint =
    Method.POST / "api" / "v1" / "token" -> handler { (request: Request) =>
      for
        params <- parseParams(request)
      yield
        Response.text("Hello World!")
    }.catchAll {
      case ex: Throwable =>
        handler(Response.internalServerError)
    }

  private def parseParams(request: Request): Task[Map[String, String]] =
    request.body.asURLEncodedForm
      .map(_.formData.flatMap(fd => fd.stringValue.map(v => fd.name -> v)).toMap)




