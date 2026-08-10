package versola

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.{AuthFactor, AuthFactorType}
import versola.util.{Base64Url, EnvName, Phone, Secret, SecureRandom}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.UUID

object BootstrapServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private def endpointId(method: String, path: String) =
    versola.central.configuration.resources.ResourceEndpointId(
      UUID.nameUUIDFromBytes(s"$method $path".getBytes(StandardCharsets.UTF_8)),
    )

  def spec = suite("BootstrapService")(
    test("adds an OTP factor and phone outside production") {
      val envName = EnvName.Test("local")

      assertTrue(
        BootstrapService.adminPhone(envName).contains(Phone("+12025551234")),
        BootstrapService.adminAuthFactors(envName) == List(
          AuthFactor(AuthFactorType.otp, required = true),
          AuthFactor(AuthFactorType.passkeyEnroll, required = true),
        ),
        BootstrapService.adminAuthFlow(envName).passkey.isDefined,
      )
    },
    test("does not add an OTP factor or phone in production") {
      assertTrue(
        BootstrapService.adminPhone(EnvName.Prod).isEmpty,
        BootstrapService.adminAuthFactors(EnvName.Prod) == List(
          AuthFactor(AuthFactorType.passkeyEnroll, required = true),
        ),
        BootstrapService.adminAuthFlow(EnvName.Prod).passkey.isDefined,
      )
    },
    test("uses the configured resource secret") {
      val configured = Secret(Array.fill(32)(7.toByte))
      val secureRandom = stub[SecureRandom]

      BootstrapService.resolveResourceSecret(Some(configured), secureRandom)
        .map(secret => assertTrue(Base64Url.encode(secret) == Base64Url.encode(configured)))
    },
    test("generates a resource secret when configuration is absent") {
      val generated = Array.fill(32)(9.toByte)
      val secureRandom = stub[SecureRandom]

      for
        _ <- secureRandom.nextBytes.succeedsWith(generated)
        secret <- BootstrapService.resolveResourceSecret(None, secureRandom)
      yield assertTrue(Base64Url.encode(secret) == Base64Url.encode(Secret(generated)))
    },
    test("resources:manage includes resource secret lifecycle endpoints") {
      assertTrue(
        BootstrapService.resourceManagementEndpointIds.contains(
          endpointId("POST", "/configuration/resources/rotate-secret"),
        ),
        BootstrapService.resourceManagementEndpointIds.contains(
          endpointId("DELETE", "/configuration/resources/previous-secret"),
        ),
      )
    },
  )
