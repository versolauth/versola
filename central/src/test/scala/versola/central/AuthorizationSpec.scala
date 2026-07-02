package versola.central

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.{AuthFlow, OAuthClientRecord, OAuthClientRepository, OAuthClientService}
import versola.central.configuration.tenants.TenantRepository
import versola.util.{Base64Url, ReloadingCache, Secret, SecureRandom, SecurityService}
import versola.util.http.Unauthorized
import zio.*
import zio.http.{Header, Request, URL}
import zio.test.*

object AuthorizationSpec extends ZIOSpecDefault, ZIOStubs:

  /** The in-memory cache holds the decrypted `central-admin` secret. */
  private val centralClient = OAuthClientRecord(
    id = CentralConfig.centralClientId,
    tenantId = CentralConfig.defaultTenantId,
    clientName = "central-admin",
    redirectUris = Set.empty,
    scope = Set.empty,
    externalAudience = Nil,
    secret = Some(TestCentralConfig.edgeSecret),
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set.empty,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default",
  )

  private def service: OAuthClientService =
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Vector(centralClient))))
    OAuthClientService.Impl(
      cache,
      stub[OAuthClientRepository],
      stub[TenantRepository],
      stub[SecureRandom],
      stub[SecurityService],
      TestCentralConfig.config,
    )

  private val env = ZEnvironment[OAuthClientService](service)

  def spec = suite("authorizeBasic")(
    test("succeeds when correct Basic auth header is provided") {
      authorizeBasic(Request.get(URL.empty).addHeader(TestCentralConfig.basicAuthHeader))
        .provideEnvironment(env)
        .map(_ => assertTrue(true))
    },
    test("fails with Unauthorized when password bytes are wrong") {
      val wrongHeader = Header.Authorization.Basic("edge", Base64Url.encode(Secret(Array.fill(32)(9.toByte))))
      authorizeBasic(Request.get(URL.empty).addHeader(wrongHeader))
        .provideEnvironment(env)
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
    test("fails with Unauthorized when no Authorization header is present") {
      authorizeBasic(Request.get(URL.empty))
        .provideEnvironment(env)
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
    test("fails with Unauthorized when Bearer token is used instead of Basic") {
      authorizeBasic(Request.get(URL.empty).addHeader(Header.Authorization.Bearer("some.jwt.token")))
        .provideEnvironment(env)
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
    test("fails with Unauthorized when password is not valid Base64URL") {
      val wrongHeader = Header.Authorization.Basic("edge", "not-valid-base64url!!!")
      authorizeBasic(Request.get(URL.empty).addHeader(wrongHeader))
        .provideEnvironment(env)
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
  ) @@ TestAspect.silentLogging
