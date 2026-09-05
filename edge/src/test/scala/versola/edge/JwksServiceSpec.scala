package versola.edge

import versola.util.{JWT, ReloadingCache}
import zio.*
import zio.test.*

object JwksServiceSpec extends ZIOSpecDefault:

  private def publicKeys(keyId: String): JWT.PublicKeys =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    val keyPair = generator.generateKeyPair().nn
    val jwk = com.nimbusds.jose.jwk.RSAKey
      .Builder(keyPair.getPublic.asInstanceOf[java.security.interfaces.RSAPublicKey])
      .keyID(keyId)
      .build()
    JWT.PublicKeys(com.nimbusds.jose.jwk.JWKSet(jwk))

  def spec = suite("JwksService")(
    test("getPublicKeys reads through to the underlying cache") {
      for
        keys <- ZIO.succeed(publicKeys("key-1"))
        cache <- Ref.make(keys)
        service = JwksService.Impl(ReloadingCache(cache))
        result <- service.getPublicKeys
      yield assertTrue(result.active.id == "key-1")
    },
    test("getPublicKeys reflects a cache update") {
      for
        cache <- Ref.make(publicKeys("key-1"))
        service = JwksService.Impl(ReloadingCache(cache))
        _ <- cache.set(publicKeys("key-2"))
        result <- service.getPublicKeys
      yield assertTrue(result.active.id == "key-2")
    },
  )
