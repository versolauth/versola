package versola.util

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{KeyUse, RSAKey}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps}

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

case class RsaKeyPair(
    keyId: String,
    publicKey: RSAPublicKey,
    privateKey: RSAPrivateKey,
):
  def toPublicJwk: Json.Obj =
    val jwk = new RSAKey.Builder(publicKey)
      .keyID(keyId)
      .algorithm(JWSAlgorithm.RS256)
      .keyUse(KeyUse.SIGNATURE)
      .build()

    jwk.toJSONString.fromJson[Json.Obj]
      .getOrElse(throw new IllegalStateException("Failed to encode JWK as JSON object"))
