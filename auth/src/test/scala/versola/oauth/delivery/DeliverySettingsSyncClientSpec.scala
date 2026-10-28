package versola.oauth.delivery

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.util.{Base64, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect.silentLogging

import java.nio.charset.StandardCharsets

object DeliverySettingsSyncClientSpec extends ZIOSpecDefault:
  private val config = TestEnvConfig.coreConfig

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  /** What central sends: a password is encrypted under the secret the two services share and encoded base64url. */
  private def transportEncrypted(plaintext: String): ZIO[SecurityService, Throwable, String] =
    ZIO.serviceWithZIO[SecurityService](_.encryptAes256(plaintext.getBytes(StandardCharsets.UTF_8), config.central.secretKey))
      .map(Base64.urlEncode)

  private def serve(status: Status, body: String): ZIO[TestClient, Nothing, Ref[Option[Request]]] =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request](request => seen.set(Some(request)).as(Response.json(body).status(status))).toRoutes,
      )
    yield seen

  private def fetch: ZIO[Client & SecurityService, Throwable, DeliverySettingsSyncClient.Fetched] =
    for
      client <- ZIO.service[Client]
      security <- ZIO.service[SecurityService]
      result <- DeliverySettingsSyncClient.Impl(config = config, centralSyncTokenService = tokenService(client), securityService = security).getAll
    yield result

  private val smtp =
    """{"host":"smtp.example.com","port":587,"username":"mailer","from":"noreply@example.com","subject":"Your code","startTls":true,"enabled":true}"""

  private def provider(method: String = "POST", url: String = "https://sms.example.com/send", enabled: Boolean = true) =
    s"""{"method":"$method","url":"$url","username":"sms","body":{"phones":"{{phone}}","mes":"{{message}}"},"enabled":$enabled}"""

  def spec = suite("DeliverySettingsSyncClient")(
    test("asks central's delivery settings sync endpoint") {
      for
        seen <- serve(Status.Ok, """{"stored":false}""")
        _ <- fetch
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(request.method == Method.GET, request.url.path.segments.toList == List("configuration", "delivery-settings", "sync"))
    },
    test("reads both blocks and decrypts each password under the shared secret") {
      for
        smtpPassword <- transportEncrypted("mail-s3cret")
        smsPassword <- transportEncrypted("sms-s3cret")
        _ <- serve(
          Status.Ok,
          s"""{"stored":true,"smtp":$smtp,"smtpPassword":"$smtpPassword","otpProvider":${provider()},"otpProviderPassword":"$smsPassword"}""",
        )
        fetched <- fetch
        smtpBlock <- ZIO.fromOption(fetched.smtp).orElseFail(RuntimeException("no smtp block"))
        smsBlock <- ZIO.fromOption(fetched.otpProvider).orElseFail(RuntimeException("no otp provider block"))
      yield assertTrue(
        smtpBlock.enabled,
        smtpBlock.settings.host == "smtp.example.com",
        smtpBlock.settings.port == 587,
        smtpBlock.settings.username == "mailer",
        smtpBlock.settings.password.contains("mail-s3cret"),
        smtpBlock.settings.from == "noreply@example.com",
        smtpBlock.settings.subject == "Your code",
        smtpBlock.settings.startTls,
        smsBlock.enabled,
        smsBlock.settings.method == Method.POST,
        smsBlock.settings.url.encode == "https://sms.example.com/send",
        smsBlock.settings.username.contains("sms"),
        smsBlock.settings.password.contains("sms-s3cret"),
        smsBlock.settings.body == Map("phones" -> "{{phone}}", "mes" -> "{{message}}"),
      )
    },
    test("trims the host, as central does when it tests the block") {
      for
        _ <- serve(Status.Ok, s"""{"stored":true,"smtp":${smtp.replace("smtp.example.com", " smtp.example.com ")}}""")
        fetched <- fetch
      yield assertTrue(fetched.smtp.exists(_.settings.host == "smtp.example.com"))
    },
    test("has no password for a block saved without one") {
      for
        _ <- serve(Status.Ok, s"""{"stored":true,"smtp":$smtp,"otpProvider":${provider()}}""")
        fetched <- fetch
      yield assertTrue(
        fetched.smtp.exists(_.settings.password.isEmpty),
        fetched.otpProvider.exists(_.settings.password.isEmpty),
      )
    },
    test("keeps a block that is switched off, marked as off") {
      for
        _ <- serve(Status.Ok, s"""{"stored":true,"otpProvider":${provider(enabled = false)}}""")
        fetched <- fetch
      yield assertTrue(fetched.smtp.isEmpty, fetched.otpProvider.exists(!_.enabled))
    },
    test("is empty when central holds nothing") {
      for
        _ <- serve(Status.Ok, """{"stored":false}""")
        fetched <- fetch
      yield assertTrue(fetched == DeliverySettingsSyncClient.Fetched.empty)
    },
    test("says so when central has no such endpoint, and leaves it to the caller what that means") {
      for
        _ <- serve(Status.NotFound, """{"message":"not found"}""")
        result <- fetch.either
      yield assertTrue(result.left.exists(_.isInstanceOf[DeliverySettingsSyncClient.EndpointMissing]))
    },
    test("fails, rather than treating it as empty, when central refuses the sync token") {
      for
        _ <- serve(Status.Unauthorized, """{}""")
        result <- fetch.either
      yield assertTrue(result.isLeft)
    },
    test("fails the fetch when a password was not encrypted under the shared secret, so the cache keeps what it has") {
      for
        smsPassword <- transportEncrypted("sms-s3cret")
        _ <- serve(
          Status.Ok,
          s"""{"stored":true,"smtp":$smtp,"smtpPassword":"${Base64.urlEncode(
              Array.fill(40)(1.toByte),
            )}","otpProvider":${provider()},"otpProviderPassword":"$smsPassword"}""",
        )
        result <- fetch.either
      yield assertTrue(result.isLeft)
    },
    test("fails the fetch for an address that is not an URL and for a sender that is not an email address") {
      for
        _ <- serve(Status.Ok, s"""{"stored":true,"otpProvider":${provider(url = "not a url")}}""")
        badUrl <- fetch.either
        _ <- serve(Status.Ok, s"""{"stored":true,"smtp":${smtp.replace("noreply@example.com", "not-an-address")}}""")
        badFrom <- fetch.either
      yield assertTrue(badUrl.isLeft, badFrom.isLeft)
    },
  ).provide(TestClient.layer, ZLayer.make[SecurityService](SecureRandom.live, SecurityService.live)) @@ silentLogging
