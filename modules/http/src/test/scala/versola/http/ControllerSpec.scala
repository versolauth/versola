package versola.http

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.util.UnitSpecBase
import zio.http.*
import zio.internal.stacktracer.SourceLocation
import zio.json.JsonCodec
import zio.json.ast.*
import zio.schema.codec.BinaryCodec
import zio.test.{TestAspect, TestConstructor, TestResult, assertTrue}
import zio.{Tag, Trace, UIO, ZIO}

abstract class ControllerSpec[C <: Controller](val controller: C) extends UnitSpecBase, ZIOStubs:
  type Service

  def jsonTestCase[Args, Result, RResult <: Result](
      description: String,
      request: Request,
      expectedResponse: Response,
      setup: Stub[Service] => UIO[Unit] = _ => ZIO.unit,
      verify: Stub[Service] => TestResult = _ => zio.test.assertTrue(true),
  )(using
      tag: Tag[Service],
      loc: SourceLocation,
      trace: Trace,
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        service <- ZIO.service[Stub[Service]]
        env <- ZIO.environment[controller.Env]
        _ <- TestClient.addRoutes(controller.routes.provideEnvironment(env))
        _ <- setup(service)

        testRequest = request
          .addHeaders(Headers(Header.Accept(MediaType.application.json)))

        response <- client.batched(testRequest)

        fixedExpectedBody =
          if expectedResponse.body.isEmpty then
            ZIO.succeed(Json.Obj())
          else
            expectedResponse.bodyAs[Json]

        (responseBody, expectedResponseBody) <- response.bodyAs[Json] <&> fixedExpectedBody
      yield assertTrue(
        response.status == expectedResponse.status,
        expectedResponse.headers.forall(header => response.headers.exists(_ == header)),
        responseBody == expectedResponseBody
      ) && verify(service)
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  given [A: JsonCodec] => BinaryCodec[A] =
    zio.schema.codec.JsonCodec.zioJsonBinaryCodec
