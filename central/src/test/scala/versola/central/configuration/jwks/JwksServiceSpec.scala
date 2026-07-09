package versola.central.configuration.jwks

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{KeyUse, RSAKey}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

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

  private def inMemoryRepo(records: Vector[JwksRecord]): ULayer[JwksRepository] =
    ZLayer.succeed(new JwksRepository:
      def getAll: Task[Vector[JwksRecord]]               = ZIO.succeed(records)
      def find(kid: String): Task[Option[JwksRecord]]    = ZIO.succeed(records.find(_.kid == kid))
      def create(kid: String, jwk: Json.Obj): Task[Unit] = ZIO.unit
      def update(kid: String, jwk: Json.Obj): Task[Unit] = ZIO.unit
      def delete(kid: String): Task[Unit]                = ZIO.unit
    )

  private def serviceFrom(records: Vector[JwksRecord]) =
    ZLayer.make[JwksService](
      inMemoryRepo(records),
      Scope.default,
      JwksService.live(Schedule.fixed(1.hour)),
    )

  def spec = suite("JwksService")(
    test("getRaw returns the configured JWKS") {
      val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj])
      (for
        service <- ZIO.service[JwksService]
        raw     <- service.getRaw
      yield assertTrue(raw == testJwks)).provide(serviceFrom(Vector(record)))
    },
    test("getPublicKeys parses the configured JWKS") {
      val record = JwksRecord("key-1", testKey.asInstanceOf[Json.Obj])
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
  )
