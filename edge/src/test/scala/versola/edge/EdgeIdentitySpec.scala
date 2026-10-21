package versola.edge

import com.nimbusds.jose.jwk.RSAKey
import versola.edge.model.{AccessToken, EdgeId}
import versola.util.{EdgeAssertion, JWT, Secret}
import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/** Boundary 2 of #555: the identity a mode selects, and what the first-party one sends. */
object EdgeIdentitySpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  /** This edge's public key as central would have registered it, which is what auth verifies
    * an assertion against. */
  private val registeredKeys = JWT.PublicKeys.fromJson(
    Json.Obj("keys" -> Json.Arr(
      RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(config.keyId).build()
        .toJSONString.fromJson[Json.Obj].getOrElse(Json.Obj()),
    )),
  )

  def spec = suite("EdgeIdentity")(
    suite("Registered")(
      test("sends an edge assertion under the registered key, bound to the token presented") {
        for
          headers <- EdgeIdentity.Registered(config).headersFor(AccessToken("at-1"))
          assertion <- ZIO.fromOption(headers.rawHeader(EdgeAssertion.HeaderName))
            .orElseFail(new RuntimeException("no edge assertion sent"))
          edgeId <- EdgeAssertion.edgeIdOf(assertion)
          accepted <- EdgeAssertion.verify(assertion, registeredKeys, "at-1").either
          rejected <- EdgeAssertion.verify(assertion, registeredKeys, "a-different-token").flip
        yield assertTrue(
          edgeId == config.id,
          accepted.isRight,
          rejected == EdgeAssertion.Error.TokenMismatch,
        )
      },
      test("sends nothing but the assertion") {
        for headers <- EdgeIdentity.Registered(config).headersFor(AccessToken("at-1"))
        // zio-http lower-cases a header's name on the way in; the value is what carries the case.
        yield assertTrue(headers.toList.map(_.headerName) == List(EdgeAssertion.HeaderName.toLowerCase))
      },
    ),
    suite("by mode")(
      test("first-party is the registered edge") {
        for identity <- ZIO.service[EdgeIdentity].provideLayer(ZLayer.succeed(config) >>> EdgeIdentity.live)
        yield assertTrue(identity.isInstanceOf[EdgeIdentity.Registered])
      },
      test("partner is refused as not implemented, the same way the configuration is") {
        for
          error <- ZIO.service[EdgeIdentity]
            .provideLayer(ZLayer.succeed(config.copy(mode = EdgeMode.Partner)) >>> EdgeIdentity.live)
            .flip
        yield assertTrue(error == EdgeMode.NotImplemented(EdgeMode.Partner))
      },
    ),
  )
