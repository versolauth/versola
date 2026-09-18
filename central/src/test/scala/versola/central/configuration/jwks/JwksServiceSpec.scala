package versola.central.configuration.jwks

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{KeyUse, RSAKey}
import versola.central.TestCentralConfig
import versola.util.{Base64, JWT, SecureRandom, SecurityService, Secret}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.spec.SecretKeySpec

object JwksServiceSpec extends ZIOSpecDefault:

  private val rsaKeyPair =
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()

  private val rsaJWK = new RSAKey.Builder(rsaKeyPair.getPublic.asInstanceOf[RSAPublicKey])
    .keyID("key-1")
    .algorithm(JWSAlgorithm.RS256)
    .keyUse(KeyUse.SIGNATURE)
    .build()

  private val testKey: Json =
    rsaJWK.toJSONString.fromJson[Json].toOption.get

  private val testJwks: Json.Obj =
    Json.Obj("keys" -> Json.Arr(testKey))

  private val encryptedPrivateKey = Secret.fromString("encrypted-pkcs8")

  /** Records the writes, so a test can assert on what the service stored rather than only on
    * what it returned. `update` and `delete` are exercised through the cache-backed reads.
    */
  private class RecordingRepo(initial: Vector[JwksRecord]) extends JwksRepository:
    val stored = Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial))

    def getAll: Task[Vector[JwksRecord]] = stored.get
    def find(kid: String): Task[Option[JwksRecord]] = stored.get.map(_.find(_.kid == kid))
    def create(kid: String, jwk: Json.Obj, privateKey: Option[Secret]): Task[Unit] =
      stored.update(_ :+ JwksRecord(kid, jwk, privateKey))
    def update(kid: String, jwk: Json.Obj): Task[Unit] =
      stored.update(_.map(record => if record.kid == kid then record.copy(jwk = jwk) else record))
    def delete(kid: String): Task[Unit] = stored.update(_.filterNot(_.kid == kid))

  private def env(records: Vector[JwksRecord], signingTenants: Vector[String] = Vector.empty) =
    val repository = RecordingRepo(records)
    val references = new SigningKeyReferences:
      def tenantsSigningWith(kid: String): UIO[Vector[String]] = ZIO.succeed(signingTenants)
    val layer = ZLayer.make[JwksService & JwksRepository](
      ZLayer.succeed[JwksRepository](repository),
      ZLayer.succeed[SigningKeyReferences](references),
      SecureRandom.live,
      SecurityService.live,
      Scope.default,
      ZLayer.succeed(TestCentralConfig.config),
      JwksService.live,
    )
    (repository, layer)

  private def serviceFrom(records: Vector[JwksRecord]) = env(records)._2

  def spec = suite("JwksService")(
    test("getRaw returns the configured JWKS") {
      val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], None)
      (for
        service <- ZIO.service[JwksService]
        raw     <- service.getRaw
      yield assertTrue(raw == testJwks)).provide(serviceFrom(Vector(record)))
    },
    test("getPublicKeys parses the configured JWKS") {
      val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], None)
      (for
        service <- ZIO.service[JwksService]
        keys    <- service.getPublicKeys
      yield assertTrue(
        keys.keys.size() == 1,
        keys.active.id == "key-1",
      )).provide(serviceFrom(Vector(record)))
    },
    test("getPublicKeys returns an empty key set for an empty JWKS") {
      (for
        service <- ZIO.service[JwksService]
        keys    <- service.getPublicKeys
      yield assertTrue(keys.keys.size() == 0)).provide(serviceFrom(Vector.empty))
    },
    // Edge reads `getRaw` on its verification path and must never receive key material it
    // cannot need; only auth reads `getSigningKeys`.
    test("getRaw publishes public halves only, even for a key central can sign with") {
      val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], Some(encryptedPrivateKey))
      (for
        service <- ZIO.service[JwksService]
        raw     <- service.getRaw
      yield assertTrue(raw == testJwks)).provide(serviceFrom(Vector(record)))
    },
    suite("getSigningKeys")(
      test("carries the encrypted private half of every signable key") {
        val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], Some(encryptedPrivateKey))
        (for
          service <- ZIO.service[JwksService]
          keys    <- service.getSigningKeys
        yield assertTrue(keys == Map("key-1" -> Base64.urlEncode(encryptedPrivateKey)))).provide(
          serviceFrom(Vector(record)),
        )
      },
      // Absent rather than present-and-empty: a tenant cannot select what is not here, and
      // auth treats a kid it has no private half for as one it cannot sign with.
      test("omits a verify-only key") {
        val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], None)
        (for
          service <- ZIO.service[JwksService]
          keys    <- service.getSigningKeys
        yield assertTrue(keys.isEmpty)).provide(serviceFrom(Vector(record)))
      },
      test("omits a key published without a usable alg") {
        val noAlg = Json.Obj("kid" -> Json.Str("key-1"), "kty" -> Json.Str("RSA"))
        val record = JwksRecord("key-1", noAlg, Some(encryptedPrivateKey))
        (for
          service <- ZIO.service[JwksService]
          keys    <- service.getSigningKeys
        yield assertTrue(keys.isEmpty)).provide(serviceFrom(Vector(record)))
      },
    ),
    test("listKeys reports what the console needs without the key material") {
      val signable = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], Some(encryptedPrivateKey))
      val verifyOnly = JwksRecord("key-2", Json.Obj("kid" -> Json.Str("key-2"), "kty" -> Json.Str("RSA")), None)
      (for
        service <- ZIO.service[JwksService]
        keys    <- service.listKeys
      yield assertTrue(
        keys.map(_.kid) == Vector("key-1", "key-2"),
        keys.map(_.canSign) == Vector(true, false),
        keys.map(_.algorithm) == Vector(Some("RS256"), None),
        keys.map(_.keyType) == Vector(Some("RSA"), Some("RSA")),
      )).provide(serviceFrom(Vector(signable, verifyOnly)))
    },
    suite("generateKey")(
      test("stores both halves and publishes the public one under the requested alg") {
        val (repository, layer) = env(Vector.empty)
        (for
          service <- ZIO.service[JwksService]
          kid <- service.generateKey(JWT.Algorithm.PS256)
          stored <- repository.getAll
          // Published before anything can be moved onto it: the first of the two steps a
          // safe rotation needs.
          published <- service.getRaw
          signing <- service.getSigningKeys
        yield assertTrue(
          stored.map(_.kid) == Vector(kid),
          stored.forall(_.canSign),
          stored.flatMap(_.algorithm) == Vector(JWT.Algorithm.PS256),
          published.toJson.contains(kid),
          signing.keySet == Set(kid),
        )).provide(layer)
      },
      test("refuses HS256, which has no public half to publish") {
        (for
          service <- ZIO.service[JwksService]
          exit <- service.generateKey(JWT.Algorithm.HS256).exit
        yield assertTrue(exit.isFailure)).provide(serviceFrom(Vector.empty))
      },
      // Kids are timestamps to the second, so seeding a set in one go would otherwise
      // collide on the primary key.
      test("gives each algorithm a distinct kid even when generated in the same second") {
        val (repository, layer) = env(Vector.empty)
        (for
          service <- ZIO.service[JwksService]
          _ <- ZIO.foreachDiscard(JwksRecord.algorithmPreference)(service.generateKey)
          stored <- repository.getAll
        yield assertTrue(
          stored.map(_.kid).distinct.size == JwksRecord.algorithmPreference.size,
          stored.flatMap(_.algorithm).toSet == JwksRecord.algorithmPreference.toSet,
        )).provide(layer)
      },
    ),
    suite("deleteKey")(
      test("deletes a key no tenant signs with") {
        val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], Some(encryptedPrivateKey))
        val (repository, layer) = env(Vector(record))
        (for
          service <- ZIO.service[JwksService]
          _ <- service.deleteKey("key-1")
          stored <- repository.getAll
        yield assertTrue(stored.isEmpty)).provide(layer)
      },
      // Retiring a key is the last step of a rotation. Deleting it while a tenant still
      // signs with it would leave that tenant issuing tokens under an unpublished kid, so
      // nothing could verify them.
      test("refuses a key a tenant still signs with, naming the tenant") {
        val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj], Some(encryptedPrivateKey))
        val (repository, layer) = env(Vector(record), signingTenants = Vector("tenant-a"))
        (for
          service <- ZIO.service[JwksService]
          exit <- service.deleteKey("key-1").exit
          stored <- repository.getAll
        yield assertTrue(
          exit.isFailure,
          exit.causeOption.exists(_.squashTrace.getMessage.contains("tenant-a")),
          stored.map(_.kid) == Vector("key-1"),
        )).provide(layer)
      },
    ),
  )
