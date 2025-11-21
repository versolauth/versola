package versola.http

import zio.*
import zio.http.{Handler, Request, Response, TestServer}
import zio.test.{Spec, TestAspect, TestResult, ZIOSpec}


abstract class HttpClientSpec extends ZIOSpec[Any]:
  override val bootstrap: ULayer[Any] = ZLayer.empty

  def testCase[R, E, A](
      description: String,
      logic: ZIO[R, E, A],
      returnedResponse: Option[Response] = None,
      verifyResult: Either[E, A],
      verifyRequest: Request => Task[TestResult],
  ): Spec[R & TestServer, Option[Nothing] | Throwable] = {
    test(description):
      for
        ref <- Ref.make[Option[Request]](None)
        _ <- TestServer.addRoutes[Any](
          Handler.fromFunctionZIO[Request] { request =>
            ref.set(Some(request)).as(returnedResponse.getOrElse(Response.ok))
          }.toRoutes,
        )
        result <- logic.either
        request <- ref.get.some
        verifyRequestResult <- verifyRequest(request)
      yield zio.test.assertTrue(result == verifyResult) && verifyRequestResult
  } @@ TestAspect.silentLogging
