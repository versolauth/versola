//> using scala 3.5.1
//> using dep com.nimbusds:nimbus-jose-jwt:10.3

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.{Base64, Date}
import scala.io.Source
import scala.util.{Try, Using}
import java.security.MessageDigest
import java.time.Instant

/*
1. Генерация закрытого ключа
2. Генерация открытого ключа
3. Конвертация открытого ключа в JWK
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -outform PEM -pubout -out public.pem
scala-cli run pem-to-jwk.scala -- public.pem
 */
@main def convertPemToJwk(pemFile: String): Unit =
  val result = for
    pemContent <- readPemFile(pemFile)
    publicKey <- parsePublicKey(pemContent)
    finalKeyId = generateKeyId(publicKey)
    jwk <- convertToJwk(publicKey, finalKeyId, "sig")
  yield jwk

  result match
    case scala.util.Success(jwk) => println(jwk)
    case scala.util.Failure(ex) =>
      System.err.println(s"Error: ${ex.getMessage}")
      System.exit(1)

def readPemFile(filePath: String): Try[String] =
  Using(Source.fromFile(filePath))(_.mkString)

def parsePublicKey(pemContent: String): Try[RSAPublicKey] =
  Try:
    val cleanPem = pemContent
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replaceAll("\\s", "")

    val keyBytes = Base64.getDecoder.decode(cleanPem)
    val keySpec = X509EncodedKeySpec(keyBytes)
    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePublic(keySpec).asInstanceOf[RSAPublicKey]

def generateKeyId(publicKey: RSAPublicKey): String =
  val digest = MessageDigest.getInstance("SHA-256")
  val keyBytes = publicKey.getEncoded
  val hash = digest.digest(keyBytes)
  Base64.getUrlEncoder.withoutPadding().encodeToString(hash).take(8)

def convertToJwk(publicKey: RSAPublicKey, keyId: String, use: String): Try[String] =
  Try:
    val builder = RSAKey.Builder(publicKey)
      .keyID(keyId)
      .keyUse(com.nimbusds.jose.jwk.KeyUse.parse(use))
      .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)

    builder.build().toJSONString