package versola.edge

import versola.edge.model.EdgeId
import versola.util.{ConfigSnapshot, JWT, Secret}
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
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private lazy val publicKeys = JWT.PublicKeys(
    com.nimbusds.jose.jwk.JWKSet(
      com.nimbusds.jose.jwk.RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey])
        .keyID(keyId)
        .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
        .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
        .build(),
    ),
  )

  private val service =
    ZIO.service[CentralSyncTokenService].provideSome[Scope](
      ZLayer.succeed(config),
      EdgeCertificateHolder.live,
      CentralSyncTokenService.live,
      ZLayer.succeed(ConfigSnapshot.disabled),
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
    test("after a switch the token is signed with the new key at once, and what central encrypted for either key can be decrypted") {
      ZIO.scoped:
        for
          tokenService <- service
          before <- tokenService.getToken
          generator = KeyPairGenerator.getInstance("RSA").nn
          _ = generator.initialize(2048)
          fresh = generator.generateKeyPair().nn
          _ <- tokenService.switchKey("kid-2", fresh.getPrivate)
          after <- tokenService.getToken
          header <- ZIO.attempt(com.nimbusds.jwt.SignedJWT.parse(after).getHeader.nn)
          keys <- tokenService.decryptionKeys(config.privateKey)
        yield assertTrue(
          before != after,
          header.getKeyID == "kid-2",
          keys == List(fresh.getPrivate, config.privateKey),
        )
    },
    test("a first-party edge decrypts with the key of its configuration") {
      ZIO.scoped:
        for
          tokenService <- service
          keys <- tokenService.decryptionKeys(config.privateKey)
        yield assertTrue(keys == List(config.privateKey))
    },
    test("a secret is decrypted with the key before the switch when the newest does not fit, and fails with the newest's error otherwise") {
      val newest = new java.security.PrivateKey { def getAlgorithm = "RSA"; def getFormat = "PKCS#8"; def getEncoded = Array.emptyByteArray }
      val older = new java.security.PrivateKey { def getAlgorithm = "RSA"; def getFormat = "PKCS#8"; def getEncoded = Array.emptyByteArray }
      for
        viaOlder <- CentralSyncTokenService.firstDecrypting(List(newest, older))(key =>
          if key.eq(older) then ZIO.succeed("plain") else ZIO.fail(RuntimeException("newest")),
        )
        failed <- CentralSyncTokenService.firstDecrypting(List(newest, older))(key =>
          ZIO.fail(RuntimeException(if key.eq(newest) then "newest" else "older")),
        ).flip
        none <- CentralSyncTokenService.firstDecrypting(Nil)(_ => ZIO.succeed("never")).flip
      yield assertTrue(viaOlder == "plain", failed.getMessage == "newest", none.isInstanceOf[IllegalStateException])
    },
  ) @@ TestAspect.silentLogging
