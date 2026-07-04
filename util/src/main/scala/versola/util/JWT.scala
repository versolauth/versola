package versola.util

import com.nimbusds.jose.crypto.{ECDSAVerifier, MACSigner, MACVerifier, RSASSASigner, RSASSAVerifier}
import com.nimbusds.jose.jwk.*
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader, JWSSigner}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.json.*
import zio.json.ast.Json
import zio.{Chunk, Clock, Duration, IO, Task, UIO, ZIO}

import java.nio.charset.StandardCharsets
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
      headers: Map[String, String] = Map.empty,
  ): Task[String] =
    Clock.instant.flatMap { now =>
      ZIO.attemptBlocking {
        val headerBuilder = JWSHeader.Builder(signature.algorithm.jwsAlgorithm)
          .keyID(signature.keyIdOpt.orNull)
          .`type`(typ.joseObjectType)
        headers.foreach((k, v) => headerBuilder.customParam(k, v))
        val header = headerBuilder.build()

        val claimsBuilder = JWTClaimsSet.Builder()
          .jwtID(java.util.UUID.randomUUID().toString)
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
              claimsBuilder.claim(key, elements.map(JsonJava.toJava).asJava)
            case Json.Obj(fields) =>
              claimsBuilder.claim(key, fields.map((k, v) => k -> JsonJava.toJava(v)).toMap.asJava)
            case Json.Null => claimsBuilder.claim(key, null)
        }

        val claimsSet = claimsBuilder.build()

        val jwt = new com.nimbusds.jwt.SignedJWT(header, claimsSet)
        val signer = signature match {
          case Signature.Asymmetric(_, _, privateKey) if signature.algorithm == Algorithm.RS256 =>
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

  /** Computes the OIDC `c_hash` / `at_hash` value for a token string (RFC OIDC Core
    * §3.3.2.11): base64url of the left-most half of the hash produced by the id token's
    * signing algorithm.
    */
  def leftHalfHash(value: String, algorithm: Algorithm): String =
    val digestName = algorithm match
      case Algorithm.RS256 | Algorithm.HS256 => "SHA-256"
    val digest = java.security.MessageDigest.getInstance(digestName)
      .digest(value.getBytes(StandardCharsets.UTF_8))
    Base64.urlEncode(digest.take(digest.length / 2))

  case class Claims(
      issuer: String,
      subject: String,
      audience: List[String],
      custom: Json.Obj,
  )

  sealed trait Signature:
    def algorithm: Algorithm = this match
      case sign: Signature.Asymmetric =>
        sign.algorithm
      case Signature.Symmetric(_) =>
        Algorithm.HS256

    def keyIdOpt: Option[String] = this match
      case sign: Signature.Asymmetric => Some(sign.keyId)
      case _: Signature.Symmetric => None

  object Signature:
    case class Asymmetric(
        override val algorithm: Algorithm,
        keyId: String,
        privateKey: PrivateKey,
    ) extends Signature

    case class Symmetric(
        key: SecretKey,
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

    given JsonDecoder[PublicKeys] = JsonDecoder[Json.Obj]
      .map(json => PublicKeys(JWKSet.parse(json.toJson)))

  case class PublicKey(key: JWK):
    def id: String = key.getKeyID
    def algorithm: Algorithm = key.getAlgorithm.getName match
      case "RS256" => Algorithm.RS256

  case class Header(header: JWSHeader):
    def get(name: String): Option[String] =
      header.getCustomParam(name) match
        case s: String => Some(s)
        case _ => None

  /**
   * Decode and parse only the JWT header segment without verifying the signature
   * or parsing the payload.
   *
   * Useful for extracting routing information (e.g. `kid`, `eid`) needed to
   * select the verification key before performing full validation via [[deserialize]].
   */
  def parseHeader[A: JsonDecoder](token: String): IO[Error, A] =
    ZIO.attempt {
      val firstSegment = token.takeWhile(_ != '.')
      val bytes = java.util.Base64.getUrlDecoder.decode(firstSegment)
      new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }.orElseFail(Error.InvalidHeader)
      .flatMap(json => ZIO.fromEither(json.fromJson[A]).orElseFail(Error.InvalidHeader))

  /**
   * Decode and parse only the JWT claims (payload) segment without verifying
   * the signature.
   *
   * Useful when the token has already been authenticated through another
   * channel (e.g. an encrypted envelope) and only the claim values are needed.
   */
  def parseClaims[A: JsonDecoder](token: String): IO[Error, A] =
    ZIO.attempt(new String(Base64.urlDecode(token.split('.')(1)), StandardCharsets.UTF_8))
      .flatMap(json => ZIO.fromEither(json.fromJson[A]))
      .orElseFail(Error.InvalidClaims)

  /**
   * Deserialize and validate a JWT with asymmetric signature.
   *
   * Performs generic JWT validation:
   * 1. JWT parsing
   * 2. Type (typ) header verification
   * 3. Signature verification using JWK set
   * 4. Expiration check
   * 5. Claims extraction and deserialization to type A
   *
   * @param token The JWT token string
   * @param keys JWK set for signature verification
   * @param typ Expected JWT type to verify against
   * @return Validated and deserialized JWT claims or error
   */
  def deserialize[A: JsonDecoder](
      token: String,
      keys: PublicKeys,
      typ: Type,
      validateExpiry: Boolean = true,
  ): IO[Error, A] =
    for
      now <- Clock.instant

      jwt <- ZIO.attempt(SignedJWT.parse(token))
        .orElseFail(Error.NotJWT)

      _ <- verifyType(jwt, typ)
      _ <- verifySignature(jwt, keys)
      _ <- if validateExpiry then checkExpiration(jwt, now) else ZIO.unit

      result <- ZIO.fromEither(claimsToJson(jwt.getJWTClaimsSet).as[A])
        .orElseFail(Error.InvalidClaims)
    yield result

  /**
   * Deserialize and validate a JWT with symmetric signature.
   *
   * Performs generic JWT validation:
   * 1. JWT parsing
   * 2. Type (typ) header verification
   * 3. Signature verification using secret key
   * 4. Expiration check
   * 5. Claims extraction and deserialization to type A
   *
   * @param token The JWT token string
   * @param key Secret key for signature verification
   * @param typ Expected JWT type to verify against
   * @return Validated and deserialized JWT claims or error
   */
  def deserialize[A: JsonDecoder](
      token: String,
      key: SecretKey,
      typ: Type,
  ): IO[Error, A] =
    for
      now <- Clock.instant

      jwt <- ZIO.attempt(SignedJWT.parse(token))
        .orElseFail(Error.NotJWT)

      _ <- verifyType(jwt, typ)
      _ <- verifySymmetricSignature(jwt, key)
      _ <- checkExpiration(jwt, now)

      result <- ZIO.fromEither(claimsToJson(jwt.getJWTClaimsSet).as[A])
        .orElseFail(Error.InvalidClaims)
    yield result


  private def verifyType(jwt: SignedJWT, expectedTyp: Type): IO[Error, Unit] =
    ZIO.attempt(Option(jwt.getHeader.getType))
      .orElseFail(Error.InvalidType)
      .flatMap {
        case None      => ZIO.unit  // missing typ header is acceptable (common in OIDC ID tokens)
        case Some(typ) => ZIO.cond(typ == expectedTyp.joseObjectType, (), Error.InvalidType)
      }

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
      jti <- ZIO.attempt(Option(jwt.getJWTClaimsSet.getJWTID))
        .orElseFail(Error.InvalidClaims)
        .someOrFail(Error.InvalidClaims)

      _ <- expTime match
        case Some(exp) if exp.toInstant.isBefore(now) =>
          ZIO.fail(Error.Expired(jti))
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
    case InvalidType
    case InvalidSignature
    case Expired(jti: String)
    case InvalidClaims
    case InvalidHeader
