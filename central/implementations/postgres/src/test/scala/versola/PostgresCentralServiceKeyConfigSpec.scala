package versola

import com.nimbusds.jose.jwk.gen.{ECKeyGenerator, RSAKeyGenerator}
import com.nimbusds.jose.jwk.{Curve, JWK}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import versola.central.CentralConfig
import versola.central.configuration.topology.CellId
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** The keys of #1050 in central's configuration: `service-key` (the private key its tokens to auth are signed with, read the way
  * an edge's `private-key` is), `auth.public-keys` (a list of strings, each the JSON text of a public RSA JWK) and `auth.cell-id`.
  * The shared `secret-key` is gone. */
object PostgresCentralServiceKeyConfigSpec extends ZIOSpecDefault:

  import PostgresCentralApp.given

  private def standardPkcs8(algorithm: String, bits: Int): String =
    val generator = KeyPairGenerator.getInstance(algorithm)
    generator.initialize(bits)
    Base64.getEncoder.encodeToString(generator.generateKeyPair().getPrivate.getEncoded)

  private val rsaPrivateKey = standardPkcs8("RSA", 2048)

  private def json(key: JWK): String = key.toJSONString

  private def publicRsa(kid: String) = RSAKeyGenerator(2048).keyID(kid).generate().toPublicJWK

  /** The file a generator writes: `auth.public-keys` is a list of strings, not of HOCON objects. */
  private def load(
      privateKey: String = rsaPrivateKey,
      publicKeys: Option[List[String]] = None,
      cellId: Option[String] = None,
  ) =
    val base = Map[String, AnyRef](
      "client-secrets-secret" -> "AAAAAAAAAAAAAAAAAAAAAA",
      "service-key.key-id" -> "central-test",
      "service-key.private-key" -> privateKey,
      "auth.url" -> "http://localhost:9003",
      "user-outbox.poll-interval" -> "1 second",
      "user-outbox.batch-size" -> Integer.valueOf(1),
      "user-outbox.lease" -> "1 minute",
      "user-outbox.max-backoff" -> "1 minute",
      "user-outbox.max-attempts" -> Integer.valueOf(3),
      "configuration-cache-refresh-interval" -> "5 minutes",
    )
    val withKeys = publicKeys.fold(base)(keys => base + ("auth.public-keys" -> keys.asJava))
    val all = cellId.fold(withKeys)(id => withKeys + ("auth.cell-id" -> id))
    ConfigProvider.fromTypesafeConfigZIO(ConfigFactory.parseMap(all.asJava)).map(_.kebabCase)
      .flatMap(_.load(deriveConfig[CentralConfig]))

  private def validated(config: CentralConfig) =
    ZIO.service[CentralConfig].provide(ZLayer.succeed(config) >>> CentralConfig.validated).either

  private def withKeys(keys: List[Json.Obj]) =
    load().map(config => config.copy(auth = config.auth.copy(publicKeys = keys)))

  private def asJson(key: JWK): Json.Obj = key.toJSONString.fromJson[Json.Obj].toOption.get

  def spec = suite("central service key and auth keys")(
    test("service-key is the key id and the PKCS#8 RSA private key in the standard alphabet, as an edge's private-key is") {
      for config <- load()
      yield assertTrue(
        config.serviceKey.keyId == "central-test",
        config.serviceKey.privateKey.getAlgorithm == "RSA",
        config.serviceKey.privateKey.getFormat == "PKCS#8",
      )
    },
    test("auth.public-keys is a list of the JSON text of JWKs, and auth.cell-id is the cell the auth is configured as") {
      val first = publicRsa("auth-1")
      val second = publicRsa("auth-2")
      for config <- load(publicKeys = Some(List(json(first), json(second))), cellId = Some("kz-1"))
      yield assertTrue(
        config.auth.publicKeys == List(asJson(first), asJson(second)),
        config.auth.cellId == Some(CellId("kz-1")),
      )
    },
    test("with neither, the configured auth has no keys of its own and no cell") {
      for config <- load()
      yield assertTrue(config.auth.publicKeys.isEmpty, config.auth.cellId.isEmpty)
    },
    test("a private key that is not PKCS#8, an EC one and an empty one are refused, and the error does not carry the value") {
      val ec = standardPkcs8("EC", 256)
      for
        garbage <- load(privateKey = "bm90LWEta2V5").exit
        wrongKind <- load(privateKey = ec).exit
        empty <- load(privateKey = "").exit
      yield assertTrue(
        garbage.isFailure,
        wrongKind.isFailure,
        empty.isFailure,
        !wrongKind.toString.contains(ec),
        !garbage.toString.contains("bm90LWEta2V5"),
      )
    },
    test("a cell id the routing key cannot carry is refused") {
      for bad <- load(cellId = Some("KZ_1")).exit
      yield assertTrue(bad.isFailure)
    },
    suite("auth.public-keys are validated at start")(
      test("public RSA keys of a size central accepts, each with a kid of its own, pass") {
        for
          config <- withKeys(List(asJson(publicRsa("auth-1")), asJson(publicRsa("auth-2"))))
          result <- validated(config)
        yield assertTrue(result.isRight)
      },
      test("no key at all passes: only the keys of the registered cells verify a caller") {
        for
          config <- withKeys(Nil)
          result <- validated(config)
        yield assertTrue(result.isRight)
      },
      test("a key with a private member, an EC key, a small key and a key with no kid are refused") {
        val unnamed = Json.Obj(asJson(publicRsa("x")).fields.filterNot(_._1 == "kid")*)
        for
          withPrivate <- withKeys(List(asJson(RSAKeyGenerator(2048).keyID("p").generate()))).flatMap(validated)
          ec <- withKeys(List(asJson(ECKeyGenerator(Curve.P_256).keyID("e").generate().toPublicJWK))).flatMap(validated)
          small <- withKeys(List(asJson(RSAKeyGenerator(1024, true).keyID("s").generate().toPublicJWK))).flatMap(validated)
          noKid <- withKeys(List(unnamed)).flatMap(validated)
        yield assertTrue(withPrivate.isLeft, ec.isLeft, small.isLeft, noKid.isLeft)
      },
      test("a kid or a key named twice is refused") {
        val key = publicRsa("auth-1")
        val renamed = Json.Obj(asJson(key).fields.map { case ("kid", _) => "kid" -> Json.Str("other"); case other => other }*)
        for
          sameKid <- withKeys(List(asJson(key), asJson(publicRsa("auth-1")))).flatMap(validated)
          sameKey <- withKeys(List(asJson(key), renamed)).flatMap(validated)
        yield assertTrue(sameKid.isLeft, sameKey.isLeft)
      },
    ),
  ) @@ TestAspect.silentLogging
