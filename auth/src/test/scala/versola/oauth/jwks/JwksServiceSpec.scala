package versola.oauth.jwks

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{KeyUse, RSAKey}
import versola.util.JWT
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/** Regression for #104: signing must never use whichever JWKS entry central currently
  * reports as [[JWT.PublicKeys.active]] -- that's just the first element of an unordered
  * list, and it can drift ahead of this instance's own static private key across a
  * rotation. [[JwksService.resolveSigningKey]] must instead pick the entry whose public
  * modulus matches the private key this instance actually holds, regardless of position.
  */
object JwksServiceSpec extends ZIOSpecDefault:
  private def rsaJwk(keyPair: java.security.KeyPair, kid: String): RSAKey =
    new RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey])
      .keyID(kid)
      .algorithm(JWSAlgorithm.RS256)
      .keyUse(KeyUse.SIGNATURE)
      .build()

  private def publicKeysOf(jwks: RSAKey*): JWT.PublicKeys =
    JWT.PublicKeys.fromJson(
      Json.Obj("keys" -> Json.Arr(jwks.map(jwk => jwk.toJSONString.fromJson[Json.Obj].toOption.get)*)),
    )

  def spec = suite("JwksService.resolveSigningKey")(
    test("picks the entry matching the private key even when a different, unrelated entry is listed first (and would be 'active')") {
      val gen = KeyPairGenerator.getInstance("RSA")
      gen.initialize(2048)
      val ownKeyPair = gen.generateKeyPair()
      val staleKeyPair = gen.generateKeyPair()

      // Simulates central having rotated in a new key that it now reports as active,
      // while this instance's own private key hasn't changed.
      val staleJwk = rsaJwk(staleKeyPair, "stale-active-kid-from-central")
      val ownJwk = rsaJwk(ownKeyPair, "own-kid")
      val publicKeys = publicKeysOf(staleJwk, ownJwk)

      val resolved = JwksService.resolveSigningKey(ownKeyPair.getPrivate, publicKeys)

      assertTrue(
        // Sanity: confirms .active would have picked the wrong key here.
        publicKeys.active.id == "stale-active-kid-from-central",
        resolved.map(_.id).contains("own-kid"),
      )
    },
    test("returns None when no JWKS entry matches the private key") {
      val gen = KeyPairGenerator.getInstance("RSA")
      gen.initialize(2048)
      val unrelatedKeyPair = gen.generateKeyPair()
      val ownKeyPair = gen.generateKeyPair()

      val publicKeys = publicKeysOf(rsaJwk(unrelatedKeyPair, "unrelated-kid"))

      assertTrue(JwksService.resolveSigningKey(ownKeyPair.getPrivate, publicKeys).isEmpty)
    },
  )
