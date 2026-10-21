package versola.oauth.jwks

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{ECKey, KeyUse, RSAKey}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.TenantId
import versola.util.{CacheSource, JWT, ReloadingCache, UnitSpecBase}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, PrivateKey}

/** Regression for #104: signing must never use whichever JWKS entry central currently
  * reports as [[JWT.PublicKeys.active]] -- that's just the first element of an unordered
  * list, and it can drift ahead of this instance's own static private key across a
  * rotation. Signing goes by the key the tenant selected in central, and where nothing was
  * selected by [[JwksService.resolveSigningKey]], which picks the entry that belongs to the
  * private key this instance actually holds (RSA modulus, or proof of possession for EC),
  * regardless of position.
  */
object JwksServiceSpec extends UnitSpecBase:
  private val tenantId = TenantId("tenant-a")

  private def rsaKeyPair: KeyPair =
    val generator = KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private def ecKeyPair: KeyPair =
    val generator = KeyPairGenerator.getInstance("EC").nn
    generator.initialize(ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair().nn

  private def rsaJwk(keyPair: KeyPair, kid: String, algorithm: JWSAlgorithm = JWSAlgorithm.RS256): RSAKey =
    new RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey])
      .keyID(kid)
      .algorithm(algorithm)
      .keyUse(KeyUse.SIGNATURE)
      .build()

  private def ecJwk(keyPair: KeyPair, kid: String): ECKey =
    new ECKey.Builder(com.nimbusds.jose.jwk.Curve.P_256, keyPair.getPublic.asInstanceOf[ECPublicKey])
      .keyID(kid)
      .algorithm(JWSAlgorithm.ES256)
      .keyUse(KeyUse.SIGNATURE)
      .build()

  private def publicKeysOf(jwks: com.nimbusds.jose.jwk.JWK*): JWT.PublicKeys =
    JWT.PublicKeys.fromJson(
      Json.Obj("keys" -> Json.Arr(jwks.map(jwk => jwk.toJSONString.fromJson[Json.Obj].toOption.get)*)),
    )

  private def snapshotWithKeyId(keyId: String): JwksService.Snapshot =
    JwksService.Snapshot(publicKeysOf(rsaJwk(rsaKeyPair, keyId)), Map.empty, fallback = None)

  class Env(snapshot: JwksService.Snapshot, synced: JwksService.Snapshot = JwksService.Snapshot(publicKeysOf(), Map.empty, None)):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(snapshot)))
    val configurationService = stub[OAuthConfigurationService]

    /** What central would answer the next sync with, i.e. what `refresh` must pull in. */
    val source: CacheSource[JwksService.Snapshot] = new CacheSource[JwksService.Snapshot]:
      override def getAll: Task[JwksService.Snapshot] = ZIO.succeed(synced)
    val service = JwksService.Impl(cache, configurationService, source)

  def spec = suite("JwksService")(
    test("getPublicKeys reads through to the underlying cache") {
      val env = Env(snapshotWithKeyId("key-1"))
      for result <- env.service.getPublicKeys
      yield assertTrue(result.active.id == "key-1")
    },
    test("getPublicKeys reflects a cache update") {
      val env = Env(snapshotWithKeyId("key-1"))
      for
        _ <- env.cache.set(snapshotWithKeyId("key-2"))
        result <- env.service.getPublicKeys
      yield assertTrue(result.active.id == "key-2")
    },
    suite("signingKey")(
      test("signs with the key the tenant selected, not with the fallback") {
        val selectedPair = ecKeyPair
        val fallbackPair = rsaKeyPair
        val snapshot = JwksService.Snapshot(
          publicKeysOf(rsaJwk(fallbackPair, "legacy-kid"), ecJwk(selectedPair, "selected-kid")),
          Map("selected-kid" -> selectedPair.getPrivate.nn),
          fallback = Some(JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "legacy-kid", fallbackPair.getPrivate.nn)),
        )
        val env = Env(snapshot)
        for
          _ <- env.configurationService.getSigningKeyId.succeedsWith(Some("selected-kid"))
          signature <- env.service.signingKey(tenantId)
        yield assertTrue(
          signature.keyId == "selected-kid",
          // The algorithm comes from the same record as the kid, so a selection cannot end
          // up signed under the algorithm of the key it replaced.
          signature.algorithm == JWT.Algorithm.ES256,
        )
      },
      test("falls back to this instance's configured key when the tenant selected nothing") {
        val fallbackPair = rsaKeyPair
        val snapshot = JwksService.Snapshot(
          publicKeysOf(rsaJwk(fallbackPair, "legacy-kid")),
          Map.empty,
          fallback = Some(JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "legacy-kid", fallbackPair.getPrivate.nn)),
        )
        val env = Env(snapshot)
        for
          _ <- env.configurationService.getSigningKeyId.succeedsWith(None)
          signature <- env.service.signingKey(tenantId)
        yield assertTrue(signature.keyId == "legacy-kid")
      },
      // The window right after a rotation: central has moved the tenant onto a key this
      // instance has not synced yet. Keeping the previous key is what lets tokens go on
      // being issued through it; failing would take the tenant down for a cache interval.
      test("falls back when the selected kid has not been synced yet") {
        val fallbackPair = rsaKeyPair
        val snapshot = JwksService.Snapshot(
          publicKeysOf(rsaJwk(fallbackPair, "legacy-kid")),
          Map.empty,
          fallback = Some(JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "legacy-kid", fallbackPair.getPrivate.nn)),
        )
        val env = Env(snapshot)
        for
          _ <- env.configurationService.getSigningKeyId.succeedsWith(Some("not-yet-synced"))
          signature <- env.service.signingKey(tenantId)
        yield assertTrue(signature.keyId == "legacy-kid")
      },
      test("falls back when central published no private half for the selected kid") {
        val selectedPair = ecKeyPair
        val fallbackPair = rsaKeyPair
        val snapshot = JwksService.Snapshot(
          publicKeysOf(rsaJwk(fallbackPair, "legacy-kid"), ecJwk(selectedPair, "verify-only-kid")),
          Map.empty,
          fallback = Some(JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "legacy-kid", fallbackPair.getPrivate.nn)),
        )
        val env = Env(snapshot)
        for
          _ <- env.configurationService.getSigningKeyId.succeedsWith(Some("verify-only-kid"))
          signature <- env.service.signingKey(tenantId)
        yield assertTrue(signature.keyId == "legacy-kid")
      },
      // A key generated in central is selectable there before it has been synced here, so
      // the sync that follows the selection is what makes the new key signable rather than
      // one more interval of the old one.
      test("a refresh makes a key that was not synced yet signable") {
        val selectedPair = ecKeyPair
        val fallbackPair = rsaKeyPair
        val fallback = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "legacy-kid", fallbackPair.getPrivate.nn)
        val env = Env(
          snapshot = JwksService.Snapshot(publicKeysOf(rsaJwk(fallbackPair, "legacy-kid")), Map.empty, Some(fallback)),
          synced = JwksService.Snapshot(
            publicKeysOf(rsaJwk(fallbackPair, "legacy-kid"), ecJwk(selectedPair, "fresh-kid")),
            Map("fresh-kid" -> selectedPair.getPrivate.nn),
            Some(fallback),
          ),
        )
        for
          _ <- env.configurationService.getSigningKeyId.succeedsWith(Some("fresh-kid"))
          before <- env.service.signingKey(tenantId)
          _ <- env.service.refresh
          after <- env.service.signingKey(tenantId)
        yield assertTrue(
          before.keyId == "legacy-kid",
          after.keyId == "fresh-kid",
          after.algorithm == JWT.Algorithm.ES256,
        )
      },
      test("fails when neither the selection nor the fallback is usable") {
        val env = Env(JwksService.Snapshot(publicKeysOf(), Map.empty, fallback = None))
        for
          _ <- env.configurationService.getSigningKeyId.succeedsWith(None)
          exit <- env.service.signingKey(tenantId).exit
        yield assertTrue(exit.isFailure)
      },
    ),
    suite("resolveSigningKey")(
      test("picks the entry matching the private key even when a different, unrelated entry is listed first (and would be 'active')") {
        val ownKeyPair = rsaKeyPair
        val staleKeyPair = rsaKeyPair

        // Simulates central having rotated in a new key that it now reports as active,
        // while this instance's own private key hasn't changed.
        val staleJwk = rsaJwk(staleKeyPair, "stale-active-kid-from-central")
        val ownJwk = rsaJwk(ownKeyPair, "own-kid")
        val publicKeys = publicKeysOf(staleJwk, ownJwk)

        val resolved = JwksService.resolveSigningKey(ownKeyPair.getPrivate.nn, publicKeys)

        assertTrue(
          // Sanity: confirms .active would have picked the wrong key here.
          publicKeys.active.id == "stale-active-kid-from-central",
          resolved.map(_.keyId).contains("own-kid"),
          // The algorithm travels with the kid it was read from, so the header a caller
          // signs under cannot disagree with the JWK behind that kid.
          resolved.map(_.algorithm).contains(JWT.Algorithm.RS256),
        )
      },
      test("returns None when no JWKS entry matches the private key") {
        val unrelatedKeyPair = rsaKeyPair
        val ownKeyPair = rsaKeyPair

        val publicKeys = publicKeysOf(rsaJwk(unrelatedKeyPair, "unrelated-kid"))

        assertTrue(JwksService.resolveSigningKey(ownKeyPair.getPrivate.nn, publicKeys).isEmpty)
      },
      // The modulus can match while the entry says nothing usable about how to sign with
      // it. Signing under a guessed algorithm would contradict the published JWKS, so the
      // entry is not a signing candidate at all.
      test("returns None when the matching entry carries no alg") {
        val ownKeyPair = rsaKeyPair

        val noAlgJwk = new RSAKey.Builder(ownKeyPair.getPublic.asInstanceOf[RSAPublicKey])
          .keyID("own-kid")
          .keyUse(KeyUse.SIGNATURE)
          .build()

        assertTrue(JwksService.resolveSigningKey(ownKeyPair.getPrivate.nn, publicKeysOf(noAlgJwk)).isEmpty)
      },
      // gen-env's deployment: auth holds an EC key and the JWKS publishes only its public half.
      test("resolves an EC private key to the ES256 entry it belongs to") {
        val ownKeyPair = ecKeyPair
        val resolved = JwksService.resolveSigningKey(ownKeyPair.getPrivate.nn, publicKeysOf(ecJwk(ownKeyPair, "own-kid")))
        assertTrue(
          resolved.map(_.keyId).contains("own-kid"),
          resolved.map(_.algorithm).contains(JWT.Algorithm.ES256),
        )
      },
      test("returns None when no EC entry belongs to the EC private key") {
        assertTrue(
          JwksService.resolveSigningKey(ecKeyPair.getPrivate.nn, publicKeysOf(ecJwk(ecKeyPair, "other-kid"))).isEmpty,
        )
      },
      test("returns None for an EC private key when the key set holds only RSA entries") {
        assertTrue(
          JwksService.resolveSigningKey(ecKeyPair.getPrivate.nn, publicKeysOf(rsaJwk(rsaKeyPair, "rsa-kid"))).isEmpty,
        )
      },
      test("returns None for an RSA private key when the key set holds only EC entries") {
        val ownKeyPair = rsaKeyPair
        assertTrue(
          JwksService.resolveSigningKey(ownKeyPair.getPrivate.nn, publicKeysOf(ecJwk(ecKeyPair, "ec-kid"))).isEmpty,
        )
      },
      test("returns None when the matching EC entry carries no alg") {
        val ownKeyPair = ecKeyPair
        val noAlgJwk = new ECKey.Builder(com.nimbusds.jose.jwk.Curve.P_256, ownKeyPair.getPublic.asInstanceOf[ECPublicKey])
          .keyID("own-kid")
          .keyUse(KeyUse.SIGNATURE)
          .build()
        assertTrue(JwksService.resolveSigningKey(ownKeyPair.getPrivate.nn, publicKeysOf(noAlgJwk)).isEmpty)
      },
      test("an EC key set with entries of both types resolves each private key to its own entry") {
        val rsaPair = rsaKeyPair
        val ecPair = ecKeyPair
        val otherRsa = rsaKeyPair
        val otherEc = ecKeyPair
        val publicKeys = publicKeysOf(
          rsaJwk(otherRsa, "other-rsa", JWSAlgorithm.PS256),
          ecJwk(otherEc, "other-ec"),
          rsaJwk(rsaPair, "own-rsa", JWSAlgorithm.PS256),
          ecJwk(ecPair, "own-ec"),
        )
        val rsa = JwksService.resolveSigningKey(rsaPair.getPrivate.nn, publicKeys)
        val ec = JwksService.resolveSigningKey(ecPair.getPrivate.nn, publicKeys)
        assertTrue(
          rsa.map(_.keyId).contains("own-rsa"),
          rsa.map(_.algorithm).contains(JWT.Algorithm.PS256),
          ec.map(_.keyId).contains("own-ec"),
          ec.map(_.algorithm).contains(JWT.Algorithm.ES256),
        )
      },
      // The resolved signature must actually sign: the matching proves the entry, this proves the
      // private key is usable under the algorithm taken from it.
      test("an ES256 token signed with the resolved EC key verifies against the entry") {
        val ownKeyPair = ecKeyPair
        val jwk = ecJwk(ownKeyPair, "own-kid")
        val resolved = JwksService.resolveSigningKey(ownKeyPair.getPrivate.nn, publicKeysOf(jwk)).get
        val jws = new com.nimbusds.jose.JWSObject(
          new com.nimbusds.jose.JWSHeader.Builder(resolved.algorithm.jwsAlgorithm).keyID(resolved.keyId).build(),
          new com.nimbusds.jose.Payload("x"),
        )
        jws.sign(new com.nimbusds.jose.crypto.ECDSASigner(resolved.privateKey.asInstanceOf[java.security.interfaces.ECPrivateKey]))
        assertTrue(jws.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(jwk)))
      },
    ),
  )
