package versola.central

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.resources.ResourceService
import versola.util.{Base64Url, Secret}
import versola.util.http.Unauthorized
import zio.*
import zio.http.{Header, Request, URL}
import zio.test.*

object AuthorizationSpec extends ZIOSpecDefault, ZIOStubs:

  private val resourceService = stub[ResourceService]
  private val env = ZEnvironment(resourceService)

  private def authorize(request: Request) =
    resourceService.verifySecret.succeedsWith(true) *> authorizeBasic(request).provideEnvironment(env)

  def spec = suite("authorizeBasic")(
    test("succeeds when correct Basic auth header is provided") {
      authorize(Request.get(URL.empty).addHeader(TestCentralConfig.basicAuthHeader))
        .map(_ => assertTrue(true))
    },
    test("fails when the resource secret uses the wrong Basic username") {
      val wrongHeader = Header.Authorization.Basic("edge", Base64Url.encode(TestCentralConfig.edgeSecret))
      authorize(Request.get(URL.empty).addHeader(wrongHeader))
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
    test("fails with Unauthorized when password bytes are wrong") {
      val wrongHeader = Header.Authorization.Basic("edge", Base64Url.encode(Secret(Array.fill(32)(9.toByte))))
      authorize(Request.get(URL.empty).addHeader(wrongHeader))
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
    test("fails with Unauthorized when no Authorization header is present") {
      authorize(Request.get(URL.empty))
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
    test("fails with Unauthorized when Bearer token is used instead of Basic") {
      authorize(Request.get(URL.empty).addHeader(Header.Authorization.Bearer("some.jwt.token")))
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
    test("fails with Unauthorized when password is not valid Base64URL") {
      val wrongHeader = Header.Authorization.Basic("edge", "not-valid-base64url!!!")
      authorize(Request.get(URL.empty).addHeader(wrongHeader))
        .exit
        .map(result => assertTrue(result == Exit.fail(Unauthorized)))
    },
  ) @@ TestAspect.silentLogging
