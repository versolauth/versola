package versola.util.http

import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.oauth.client.model.{ClientId, ClientIdWithAssertion, ClientIdWithSecret}
import versola.util.ClientAssertion
import zio.http.{Form, Header, Headers, Method, Request, URL}
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.time.Instant
import java.util.Date

/** Which credential a request is read as carrying, before anything is verified.
  *
  * The rule this file owns is a parsing one: an RFC 7523 assertion is the whole credential,
  * so a form that pairs one with a secret, names a different client, or sends half the pair
  * is a client asking for two methods or for none -- and the answer has to be a refusal
  * rather than a fall back to the weaker method it also sent.
  */
object ExtractCredentialsSpec extends ZIOSpecDefault:

  private val clientId = "assertion-client"

  private val keyPairGenerator = KeyPairGenerator.getInstance("EC")
  keyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val privateKey = keyPairGenerator.generateKeyPair().getPrivate.asInstanceOf[ECPrivateKey]

  private val assertion: String =
    val claims = JWTClaimsSet.Builder()
      .issuer(clientId)
      .subject(clientId)
      .audience("https://auth.example/token")
      .jwtID("jti-1")
      .expirationTime(Date.from(Instant.now().plusSeconds(60)))
      .build()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec-1").build(), claims)
    jwt.sign(ECDSASigner(privateKey))
    jwt.serialize()

  private def request(headers: Headers = Headers.empty): Request =
    Request(method = Method.POST, url = URL.empty / "token", headers = headers)

  private val basic = Headers(Header.Authorization.Basic("basic-client", "secret"))

  private def extract(form: Form, headers: Headers = Headers.empty) =
    request(headers).extractCredentials(form).option

  def spec = suite("extractCredentials")(
    test("reads an assertion the form carries on its own") {
      for result <- extract(Form.fromStrings(
          "client_assertion_type" -> ClientAssertion.Type,
          "client_assertion" -> assertion,
        ))
      yield assertTrue(result == Some(ClientIdWithAssertion(ClientId(clientId), assertion)))
    },
    test("reads the client from the assertion's sub, which RFC 7521 §4.2 lets client_id omit") {
      for result <- extract(Form.fromStrings(
          "client_assertion_type" -> ClientAssertion.Type,
          "client_assertion" -> assertion,
          "client_id" -> clientId,
        ))
      yield assertTrue(result == Some(ClientIdWithAssertion(ClientId(clientId), assertion)))
    },
    test("refuses a client_id naming a client the assertion does not") {
      for result <- extract(Form.fromStrings(
          "client_assertion_type" -> ClientAssertion.Type,
          "client_assertion" -> assertion,
          "client_id" -> "someone-else",
        ))
      yield assertTrue(result.isEmpty)
    },
    test("refuses a secret sent beside the assertion rather than authenticating by it") {
      for result <- extract(Form.fromStrings(
          "client_assertion_type" -> ClientAssertion.Type,
          "client_assertion" -> assertion,
          "client_id" -> clientId,
          "client_secret" -> "a-secret",
        ))
      yield assertTrue(result.isEmpty)
    },
    test("refuses Basic sent beside the assertion rather than authenticating by it") {
      for result <- extract(
          Form.fromStrings(
            "client_assertion_type" -> ClientAssertion.Type,
            "client_assertion" -> assertion,
          ),
          basic,
        )
      yield assertTrue(result.isEmpty)
    },
    test("refuses an assertion with no client_assertion_type") {
      for result <- extract(Form.fromStrings("client_assertion" -> assertion))
      yield assertTrue(result.isEmpty)
    },
    test("refuses a client_assertion_type with no assertion") {
      for result <- extract(Form.fromStrings("client_assertion_type" -> ClientAssertion.Type))
      yield assertTrue(result.isEmpty)
    },
    test("refuses a client_assertion_type naming a method this server does not implement") {
      for result <- extract(Form.fromStrings(
          "client_assertion_type" -> "urn:ietf:params:oauth:client-assertion-type:saml2-bearer",
          "client_assertion" -> assertion,
        ))
      yield assertTrue(result.isEmpty)
    },
    test("refuses an assertion that is not a JWT, rather than falling back to the secret it also sent") {
      for result <- extract(Form.fromStrings(
          "client_assertion_type" -> ClientAssertion.Type,
          "client_assertion" -> "not-a-jwt",
          "client_id" -> clientId,
          "client_secret" -> "a-secret",
        ))
      yield assertTrue(result.isEmpty)
    },
    test("leaves the secret methods alone when the form attempts no assertion") {
      for
        post <- extract(Form.fromStrings("client_id" -> "post-client", "client_secret" -> "c2VjcmV0"))
        basicOnly <- extract(Form.empty, basic)
      yield assertTrue(
        post.exists(_.clientId == ClientId("post-client")),
        basicOnly.exists(_.clientId == ClientId("basic-client")),
      )
    },
  )
