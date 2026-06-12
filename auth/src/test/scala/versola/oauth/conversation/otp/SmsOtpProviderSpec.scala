package versola.oauth.conversation.otp

import versola.auth.TestEnvConfig
import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.util.{CoreConfig, Phone}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.silentLogging

object SmsOtpProviderSpec extends ZIOSpecDefault:

  private val phone = Phone("+12025551234")
  private val code = OtpCode("123456")
  private val template = OtpTemplate("Use {{code}} to login")
  private val expectedMessage = "Use 123456 to login"

  private def makeConfig(
      username: Option[String] = None,
      password: Option[String] = None,
      body: Map[String, String] = Map(
        "login" -> "versola",
        "phones" -> "{{phone}}",
        "mes" -> "{{message}}",
      ),
  ) =
    TestEnvConfig.coreConfig.copy(
      otpProvider = Some(
        CoreConfig.OtpProvider(
          method = Method.POST,
          url = URL.empty,
          username = username,
          password = password,
          body = body,
        )
      )
    )

  def spec = suite("SmsOtpProvider")(
    test("renders placeholders into JSON body and sends POST") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { req =>
            seen.set(Some(req)).as(Response.ok)
          }.toRoutes
        )
        provider <- ZIO.service[SmsOtpProvider]
        _ <- provider.sendOtp(phone, code, template)
        request <- seen.get.someOrFail(RuntimeException("No request captured"))
        bodyStr <- request.body.asString
        body <- ZIO.fromEither(bodyStr.fromJson[Map[String, String]])
      yield assertTrue(
        request.method == Method.POST,
        request.header(Header.ContentType).exists(_.mediaType == MediaType.application.json),
        body("login") == "versola",
        body("phones") == phone,
        body("mes") == expectedMessage,
      )
    }.provide(TestClient.layer, ZLayer.succeed(makeConfig()), SmsOtpProvider.live),

    test("adds Basic-Auth header when credentials are configured") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { req =>
            seen.set(Some(req)).as(Response.ok)
          }.toRoutes
        )
        provider <- ZIO.service[SmsOtpProvider]
        _ <- provider.sendOtp(phone, code, template)
        request <- seen.get.someOrFail(RuntimeException("No request captured"))
      yield assertTrue(
        request.header(Header.Authorization) match
          case Some(Header.Authorization.Basic(_, _)) => true
          case _ => false
      )
    }.provide(
      TestClient.layer,
      ZLayer.succeed(makeConfig(username = Some("user"), password = Some("pass"))),
      SmsOtpProvider.live,
    ),
    test("omits Authorization header when credentials are absent") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { req =>
            seen.set(Some(req)).as(Response.ok)
          }.toRoutes
        )
        provider <- ZIO.service[SmsOtpProvider]
        _ <- provider.sendOtp(phone, code, template)
        request <- seen.get.someOrFail(RuntimeException("No request captured"))
      yield assertTrue(request.header(Header.Authorization).isEmpty)
    }.provide(TestClient.layer, ZLayer.succeed(makeConfig()), SmsOtpProvider.live),
    test("fails when provider responds with non-2xx status") {
      for
        _ <- TestClient.addRoutes(
          Handler.fromFunction[Request](_ => Response.status(Status.ServiceUnavailable)).toRoutes
        )
        provider <- ZIO.service[SmsOtpProvider]
        result <- provider.sendOtp(phone, code, template).exit
      yield assertTrue(result.isFailure)
    }.provide(TestClient.layer, ZLayer.succeed(makeConfig()), SmsOtpProvider.live),
    test("does not send anything when otp provider is not configured") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { req =>
            seen.set(Some(req)).as(Response.ok)
          }.toRoutes
        )
        provider <- ZIO.service[SmsOtpProvider]
        _ <- provider.sendOtp(phone, code, template)
        request <- seen.get
      yield assertTrue(request.isEmpty)
    }.provide(
      TestClient.layer,
      ZLayer.succeed(TestEnvConfig.coreConfig.copy(otpProvider = None)),
      SmsOtpProvider.live,
    ),
  ) @@ silentLogging
