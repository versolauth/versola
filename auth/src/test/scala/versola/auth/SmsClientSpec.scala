package versola.auth
object SmsClientSpec

/*object SmsClientSpec extends HttpClientSpec:

  val testPhone = Phone("+77079037447")
  val testOtpCode = OtpCode("123456")

  val spec = suite("SmsClient")(
    suite("sendSms")(
      testCase[SmsClient, Throwable, Unit](
        description = "send SMS with correct request format",
        logic = ZIO.serviceWithZIO[SmsClient](_.sendSms(testPhone, testOtpCode)),
        verifyResult = Right(()),
        verifyRequest = request =>
          request.body.asURLEncodedForm.map: formData =>
            assertTrue(
              request.method == Method.POST,
              request.path.encode == "/sys/send.php",
              request.headers.get(Header.ContentType).contains(Header.ContentType(MediaType.application.`x-www-form-urlencoded`)),
              formData.formData == Chunk(
                FormField.simpleField("charset", "utf-8"),
                FormField.simpleField("login", "test_login"),
                FormField.simpleField("psw", "test_password"),
                FormField.simpleField("sender", "Dvor"),
                FormField.simpleField("fmt", "3"),
                FormField.simpleField("phones", testPhone),
                FormField.simpleField("mes", s"Dvor код подтверждения - $testOtpCode."),
              )
            ),
      ),
      testCase[SmsClient, Throwable, Unit](
        description = "handle server error response gracefully",
        logic = ZIO.serviceWithZIO[SmsClient](_.sendSms(testPhone, testOtpCode)),
        returnedResponse = Some(Response.internalServerError("Server error")),
        verifyResult = Right(()), // Should not fail on server errors
        verifyRequest = request => ZIO.succeed(assertTrue(request.method == Method.POST)),
      ),
    ),
  ).provide(
    Client.default,
    TestServer.default,
    SmsClient.live,
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[TestServer](_.port)
        .map(port => SmsConfig(
          login = "test_login",
          password = "test_password",
          url = URL.root.port(port)
        )),
  )*/
