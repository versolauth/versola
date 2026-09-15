package versola.util

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{Curve, ECKey, KeyUse}
import zio.json.ast.Json
import zio.json.DecoderOps

import java.security.interfaces.{ECPrivateKey, ECPublicKey}

case class EcKeyPair(
    keyId: String,
    publicKey: ECPublicKey,
    privateKey: ECPrivateKey,
):
  def toPublicJwk: Json.Obj =
    val jwk = new ECKey.Builder(Curve.P_256, publicKey)
      .keyID(keyId)
      .algorithm(JWSAlgorithm.ES256)
      .keyUse(KeyUse.SIGNATURE)
      .build()

    jwk.toJSONString.fromJson[Json.Obj]
      .getOrElse(throw new IllegalStateException("Failed to encode JWK as JSON object"))