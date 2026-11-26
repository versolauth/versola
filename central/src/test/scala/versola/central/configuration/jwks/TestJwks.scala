package versola.central.configuration.jwks

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.gen.{ECKeyGenerator, RSAKeyGenerator}
import com.nimbusds.jose.jwk.{Curve, JWK, KeyUse}
import zio.json.*
import zio.json.ast.Json

/** Real keys, generated once per run: validation parses the key material, so a hand-written placeholder would not do. */
object TestJwks:
  private val rsa = RSAKeyGenerator(2048).keyID("rsa-1").algorithm(JWSAlgorithm.PS256).keyUse(KeyUse.SIGNATURE).generate()
  private val ec = ECKeyGenerator(Curve.P_256).keyID("ec-1").algorithm(JWSAlgorithm.ES256).keyUse(KeyUse.SIGNATURE).generate()

  private def obj(json: String): Json.Obj =
    json.fromJson[Json.Obj].fold(error => throw IllegalStateException(error), identity)

  private def publicOf(key: JWK): Json.Obj = obj(key.toPublicJWK.nn.toJSONString.nn)

  val rsaPublic: Json.Obj = publicOf(rsa)
  val ecPublic: Json.Obj = publicOf(ec)

  /** The same keys with every private member the formats define, as an export of the full key carries them. */
  val rsaPrivate: Json.Obj = obj(rsa.toJSONString.nn)
  val ecPrivate: Json.Obj = obj(ec.toJSONString.nn)

  def without(jwk: Json.Obj, names: String*): Json.Obj = Json.Obj(jwk.fields.filterNot((name, _) => names.contains(name)))

  def withField(jwk: Json.Obj, name: String, value: Json): Json.Obj =
    Json.Obj(jwk.fields.filterNot(_._1 == name) :+ (name -> value))
