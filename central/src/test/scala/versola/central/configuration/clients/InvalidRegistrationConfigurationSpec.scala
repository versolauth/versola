package versola.central.configuration.clients

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.RSAKey
import versola.central.configuration.roles.RoleId
import versola.util.{JsonWebKeySet, PrivateJsonWebKey, UnitSpecBase}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.interfaces.RSAPublicKey

object InvalidRegistrationConfigurationSpec extends UnitSpecBase:

  private val clientId = ClientId("registration-client")

  private val signingKeyPair =
    val generator = java.security.KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private def jwkDocument(kid: String, withPrivate: Boolean): Json.Obj =
    val builder = RSAKey.Builder(signingKeyPair.getPublic.nn.asInstanceOf[RSAPublicKey])
      .keyID(kid)
      .algorithm(JWSAlgorithm.PS256)
    if withPrivate then builder.privateKey(signingKeyPair.getPrivate.nn)
    builder.build().nn.toJSONString.nn.fromJson[Json.Obj].toOption.get

  private val edgeSigningKey = PrivateJsonWebKey(jwkDocument("edge-key", withPrivate = true))
  private val publishedJwks = JsonWebKeySet(
    Json.Obj("keys" -> Json.Arr(jwkDocument("edge-key", withPrivate = false))),
  )

  private val edgeSigningKeySuite = suite("validateEdgeSigningKey")(
    test("accepts a signing key whose public half the client's jwks publishes") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(clientId, Some(edgeSigningKey), Some(publishedJwks))
          .isEmpty,
      )
    },
    test("leaves a client that registers no signing key alone") {
      assertTrue(
        InvalidRegistrationConfiguration.validateEdgeSigningKey(clientId, None, None).isEmpty,
      )
    },
    test("rejects a signing key with no jwks to verify what it signs") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(clientId, Some(edgeSigningKey), None)
          .exists(_.reason.contains("needs jwks")),
      )
    },
    test("rejects a signing key the client's jwks does not publish") {
      val other = JsonWebKeySet(
        Json.Obj("keys" -> Json.Arr(jwkDocument("some-other-key", withPrivate = false))),
      )
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(clientId, Some(edgeSigningKey), Some(other))
          .exists(_.reason.contains("does not publish")),
      )
    },
    test("rejects a public key registered as a signing key") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(
            clientId,
            Some(PrivateJsonWebKey(jwkDocument("edge-key", withPrivate = false))),
            Some(publishedJwks),
          )
          .exists(_.reason.contains("private")),
      )
    },
  )

  def spec = suite("InvalidRegistrationConfiguration")(
    edgeSigningKeySuite,
    test("accepts multiple assigned roles") {
      val flow = RegistrationFlow.default.copy(
        roleIds = Set(RoleId("user"), RoleId("member")),
      )

      assertTrue(
        InvalidRegistrationConfiguration.validate(clientId, Some(AuthFlow.default), Some(flow)).isEmpty,
      )
    },
    test("rejects a registration flow without an assigned role") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(roleIds = Set.empty)),
      )

      assertTrue(result.exists(_.reason == "registration requires at least one assigned role"))
    },
    test("rejects password setup without credential verification") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(steps = List(RegistrationStep.SetPassword()))),
      )

      assertTrue(result.exists(_.reason == "registration must start with OTP verification"))
    },
    test("rejects account setup before credential verification") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(steps = List(RegistrationStep.PasskeyEnroll(), RegistrationStep.Otp()))),
      )

      assertTrue(result.exists(_.reason == "registration must start with OTP verification"))
    },
  )