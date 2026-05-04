package versola.util

import com.nimbusds.jose.crypto.{ECDSAVerifier, MACSigner, MACVerifier, RSASSASigner, RSASSAVerifier}
import com.nimbusds.jose.jwk.*
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader, JWSSigner}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.json.*
import zio.json.ast.Json
import zio.{Chunk, Clock, Duration, IO, Task, ZIO}

import java.security.PrivateKey
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey
import scala.jdk.CollectionConverters.*

object JWT:
  def serialize(
      claims: Claims,
      ttl: Duration,
      signature: Signature,
      typ: Type = Type.JWT,
  ): Task[String] =
    Clock.instant.flatMap { now =>
      ZIO.attemptBlocking {
        val header = JWSHeader.Builder(signature.algorithm.jwsAlgorithm)
          .keyID(signature.keyId.orNull)
          .build()

        val claimsBuilder = JWTClaimsSet.Builder()
          .issuer(claims.issuer)
          .subject(claims.subject)
          .audience(claims.audience.asJava)
          .issueTime(Date.from(now))
          .expirationTime(Date.from(now.plusSeconds(ttl.toSeconds)))

        claims.custom.fields.foreach { (key, value) =>
          value match
            case Json.Str(s) => claimsBuilder.claim(key, s)
            case Json.Num(n) => claimsBuilder.claim(key, BigDecimal(n).bigDecimal)
            case Json.Bool(b) => claimsBuilder.claim(key, b)
            case Json.Arr(elements) =>
              claimsBuilder.claim(key, elements.map(jsonToJava).asJava)
            case Json.Obj(fields) =>
              claimsBuilder.claim(key, fields.map((k, v) => k -> jsonToJava(v)).toMap.asJava)
            case Json.Null => claimsBuilder.claim(key, null)
        }

        val claimsSet = claimsBuilder.build()

        val jwt = new com.nimbusds.jwt.SignedJWT(header, claimsSet)
        val signer = signature match {
          case Signature.Asymmetric(_, privateKey) if signature.algorithm == Algorithm.RS256 =>
            new RSASSASigner(privateKey)
          case Signature.Symmetric(key) =>
            new MACSigner(key)
          case _ =>
            throw new IllegalArgumentException("Unsupported signature type")
        }
        jwt.sign(signer)
        jwt.serialize()
      }
    }

  private def jsonToJava(json: Json): Any =
    json match
      case Json.Str(s) => s
      case Json.Num(n) => BigDecimal(n).bigDecimal
      case Json.Bool(b) => b
      case Json.Arr(elements) => elements.map(jsonToJava).asJava
      case Json.Obj(fields) => fields.map((k, v) => k -> jsonToJava(v)).toMap.asJava
      case Json.Null => null

  case class Claims(
      issuer: String,
      subject: String,
      audience: List[String],
      custom: Json.Obj,
  )

  sealed trait Signature:
    def keyId: Option[String] = this match
      case Signature.Asymmetric(publicKeys, _) =>
        Some(publicKeys.active.id)
      case _ =>
        None

    def algorithm: Algorithm = this match
      case Signature.Asymmetric(publicKeys, _) =>
        publicKeys.active.algorithm
      case Signature.Symmetric(_) =>
        Algorithm.HS256

  object Signature:
    case class Asymmetric(
        publicKeys: PublicKeys,
        privateKey: PrivateKey,
    ) extends Signature

    case class Symmetric(
        key: SecretKey
    ) extends Signature

  enum Type(val joseObjectType: JOSEObjectType):
    case JWT extends Type(JOSEObjectType.JWT)
    case AccessToken extends Type(JOSEObjectType("at+jwt"))

  enum Algorithm(val jwsAlgorithm: JWSAlgorithm):
    case RS256 extends Algorithm(JWSAlgorithm.RS256)
    case HS256 extends Algorithm(JWSAlgorithm.HS256)

  case class PublicKeys(keys: JWKSet):
    def active: PublicKey = PublicKey(keys.getKeys.get(0))
    override def toString: String = keys.toString(true)

  object PublicKeys:
    def fromJson(json: Json.Obj): PublicKeys =
      PublicKeys(JWKSet.parse(json.toJson))

  case class PublicKey(key: JWK):
    def id: String = key.getKeyID
    def algorithm: Algorithm = key.getAlgorithm.getName match
      case "RS256" => Algorithm.RS256

  /**
   * Deserialize and validate a JWT with asymmetric signature.
   *
   * Performs generic JWT validation:
   * 1. JWT parsing
   * 2. Signature verification using JWK set
   * 3. Expiration check
   * 4. Claims extraction and deserialization to type A
   *
   * @param token The JWT token string
   * @param keys JWK set for signature verification
   * @return Validated and deserialized JWT claims or error
   */
  def deserialize[A: JsonDecoder](
      token: String,
      keys: PublicKeys,
  ): IO[Error, A] =
    for
      now <- Clock.instant

      jwt <- ZIO.attempt(SignedJWT.parse(token))
        .orElseFail(Error.NotJWT)

      _ <- verifySignature(jwt, keys)
      _ <- checkExpiration(jwt, now)

      result <- ZIO.fromEither(claimsToJson(jwt.getJWTClaimsSet).as[A])
        .orElseFail(Error.InvalidClaims)
    yield result

  /**
   * Deserialize and validate a JWT with symmetric signature.
   *
   * Performs generic JWT validation:
   * 1. JWT parsing
   * 2. Signature verification using secret key
   * 3. Expiration check
   * 4. Claims extraction and deserialization to type A
   *
   * @param token The JWT token string
   * @param key   Secret key for signature verification
   * @return Validated and deserialized JWT claims or error
   */
  def deserialize[A: JsonDecoder](token: String, key: SecretKey): IO[Error, A] =
    for
      now <- Clock.instant

      jwt <- ZIO.attempt(SignedJWT.parse(token))
        .orElseFail(Error.NotJWT)

      _ <- verifySymmetricSignature(jwt, key)
      _ <- checkExpiration(jwt, now)

      result <- ZIO.fromEither(claimsToJson(jwt.getJWTClaimsSet).as[A])
        .orElseFail(Error.InvalidClaims)
    yield result

  private def verifySymmetricSignature(jwt: SignedJWT, key: SecretKey): IO[Error, Unit] =
    ZIO.attempt(jwt.verify(MACVerifier(key)))
      .orElseFail(Error.InvalidSignature)
      .filterOrFail(identity)(Error.InvalidSignature)
      .unit

  private def verifySignature(jwt: SignedJWT, keys: PublicKeys): IO[Error, Unit] =
    for
      jwk <- ZIO.attempt(Option(jwt.getHeader.getKeyID))
        .orElseFail(Error.InvalidSignature)
        .someOrFail(Error.InvalidSignature)
        .map(id => Option(keys.keys.getKeyByKeyId(id)))
        .someOrFail(Error.InvalidSignature)

      _ <- ZIO.attempt(
        jwk match
          case key: RSAKey => jwt.verify(RSASSAVerifier(key))
          case key: ECKey => jwt.verify(ECDSAVerifier(key))
          case key: OctetSequenceKey => jwt.verify(MACVerifier(key))
          case _ => false,
      )
        .orElseFail(Error.InvalidSignature)
        .filterOrFail(identity)(Error.InvalidSignature)
        .unit
    yield ()

  private def checkExpiration(jwt: SignedJWT, now: Instant): IO[Error, Unit] =
    for
      expTime <- ZIO.attempt(Option(jwt.getJWTClaimsSet.getExpirationTime))
        .orElseFail(Error.InvalidClaims)
      _ <- expTime match
        case Some(exp) if exp.toInstant.isBefore(now) =>
          ZIO.fail(Error.Expired)
        case _ =>
          ZIO.unit
    yield ()

  private def javaObjectToJson(obj: Any): Json =
    obj match
      case null => Json.Null
      case map: java.util.Map[?, ?] =>
        val fields = map.asScala.map { case (k, v) =>
          k.toString -> javaObjectToJson(v)
        }
        Json.Obj(Chunk.fromIterable(fields))
      case list: java.util.List[?] =>
        Json.Arr(Chunk.fromIterable(list.asScala.map(javaObjectToJson)))
      case s: String => Json.Str(s)
      case b: java.lang.Boolean => Json.Bool(b)
      case d: java.util.Date => Json.Num(d.toInstant.getEpochSecond)
      case n: java.lang.Integer => Json.Num(n.longValue())
      case n: java.lang.Long => Json.Num(n)
      case n: java.lang.Short => Json.Num(n.longValue())
      case n: java.lang.Byte => Json.Num(n.longValue())
      case n: java.lang.Double => Json.Num(n)
      case n: java.lang.Float => Json.Num(n.doubleValue())
      case n: java.math.BigInteger => Json.Num(BigDecimal(n))
      case n: java.math.BigDecimal => Json.Num(BigDecimal(n))
      case other => Json.Str(other.toString)

  private def claimsToJson(claims: JWTClaimsSet): Json.Obj =
    val claimsMap = claims.getClaims.asScala
    val fields = claimsMap.map { case (key, value) =>
      key -> javaObjectToJson(value)
    }
    Json.Obj(Chunk.fromIterable(fields))

  enum Error:
    case NotJWT
    case InvalidSignature
    case Expired
    case InvalidClaims
