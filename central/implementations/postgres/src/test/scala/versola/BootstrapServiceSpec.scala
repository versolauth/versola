package versola

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.{AuthFactor, AuthFactorType}
import versola.central.configuration.{InjectRule, InjectTarget}
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
    test("auth-settings:manage covers the account page and every one of its APIs") {
      assertTrue(
        BootstrapService.accountEndpointIds == Set(
          endpointId("GET", "/settings"),
          endpointId("DELETE", "/settings/sessions"),
          endpointId("PATCH", "/settings/passkeys"),
          endpointId("DELETE", "/settings/passkeys"),
          endpointId("POST", "/settings/passkeys/register/start"),
          endpointId("POST", "/settings/passkeys/register/finish"),
        ),
      )
    },
      test("account endpoints inject trusted caller context and no step-up policy yet") {
        val expectedQueryInjects = Vector(
          InjectRule(InjectTarget.query, "userId", "token.sub"),
          InjectRule(InjectTarget.query, "clientId", "token.client_id"),
          InjectRule(InjectTarget.query, "sessionId", "token.sid"),
        )
        val expectedBodyInjects = Vector(
          InjectRule(InjectTarget.body, "userId", "token.sub"),
          InjectRule(InjectTarget.body, "clientId", "token.client_id"),
        )
        assertTrue(
          BootstrapService.accountEndpointRecords.forall: endpoint =>
            endpoint.inject == BootstrapService.accountCallerInjects(endpoint.method, endpoint.path),
          BootstrapService.accountEndpointRecords.exists(_.inject == expectedQueryInjects),
          BootstrapService.accountEndpointRecords.exists(_.inject == expectedBodyInjects),
          BootstrapService.accountEndpointRecords.forall(_.stepUpCondition.isEmpty),
          BootstrapService.accountEndpointRecords.forall(_.stepUpAcr.isEmpty),
          BootstrapService.accountEndpointRecords.forall(_.maxAge.isEmpty),
        )
      },
      test("session revocation is denied for the caller's own session") {
        assertTrue(
          BootstrapService.accountEndpointRecords
            .filter(endpoint => endpoint.method == "DELETE" && endpoint.path == "/settings/sessions")
            .map(_.allowExpression) == List(Some("token.sid != request.body.targetSessionId")),
          BootstrapService.accountEndpointRecords
            .filterNot(endpoint => endpoint.method == "DELETE" && endpoint.path == "/settings/sessions")
            .forall(_.allowExpression.isEmpty),
        )
      },
  )
