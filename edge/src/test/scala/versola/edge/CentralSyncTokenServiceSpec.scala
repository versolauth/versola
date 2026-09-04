package versola.edge

import versola.edge.model.EdgeId
import versola.util.{JWT, Secret}
import zio.*
import zio.http.URL
import zio.json.JsonCodec
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object CentralSyncTokenServiceSpec extends ZIOSpecDefault:

  private case class Claims(iss: String, sub: String, aud: List[String]) derives JsonCodec

  private lazy val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val keyId = "kid-1"

  private lazy val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = keyId,
    privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey],
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private lazy val publicKeys = JWT.PublicKeys(
    com.nimbusds.jose.jwk.JWKSet(
      com.nimbusds.jose.jwk.RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey])
        .keyID(keyId)
        .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
        .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
        .build()
    )
  )

  private val service =
    ZIO.service[CentralSyncTokenService].provideSome[Scope](
      ZLayer.succeed(config),
      CentralSyncTokenService.live,
    )

  def spec = suite("CentralSyncTokenService")(
    test("mints a token central can verify with the edge signing key") {
      ZIO.scoped:
        for
          tokenService <- service
          token <- tokenService.getToken
          claims <- JWT.deserialize[Claims](token, publicKeys, JWT.Type.JWT)
        yield assertTrue(
          claims.iss == "edge",
          claims.sub == "edge",
          claims.aud == List("central"),
        )
    },
    test("stamps the edge id into the token header so central knows who is calling") {
      ZIO.scoped:
        for
          tokenService <- service
          token <- tokenService.getToken
          header <- ZIO.attempt(com.nimbusds.jwt.SignedJWT.parse(token).getHeader.nn)
        yield assertTrue(
          header.getCustomParam("edge_id") == "edge-1",
          header.getKeyID == keyId,
        )
    },
    test("serves the cached token rather than re-signing on every call") {
      ZIO.scoped:
        for
          tokenService <- service
          first <- tokenService.getToken
          second <- tokenService.getToken
        yield assertTrue(first == second)
    },
  ) @@ TestAspect.silentLogging
