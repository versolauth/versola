package versola.oauth.model

import com.nimbusds.jose.crypto.{ECDSAVerifier, MACVerifier, RSASSAVerifier}
import com.nimbusds.jose.jwk.{ECKey, JWKSet, OctetSequenceKey, RSAKey}
import com.nimbusds.jwt.{JWTClaimNames, JWTClaimsSet, SignedJWT}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.CoreConfig
import zio.json.*
import zio.json.ast.Json
import zio.{Chunk, Clock, IO, ZIO}

import java.time.Instant
import scala.jdk.CollectionConverters.*

case class AccessToken(
    @jsonField("sub") userId: UserId,
    @jsonField("client_id") clientId: ClientId,
    scope: Set[ScopeToken],
    @jsonField("requested_claims") requestedClaims: Option[RequestedClaims],
    @jsonField("ui_locales") uiLocales: Option[Vector[String]],
    @jsonField("exp") expiresAt: Instant,
    @jsonField("iat") issuedAt: Instant,
    @jsonField("nbf") notBefore: Option[Instant],
    @jsonField("aud") audience: Vector[ClientId],
    @jsonField("iss") issuer: Option[String],
    @jsonField("jti") jwtId: Option[String],
)

object AccessToken:
  enum Error:
    case NotJWT
    case InvalidSignature
    case Expired
    case InvalidClaims

  given JsonDecoder[UserId] = JsonDecoder[String].mapOrFail: s =>
    UserId.parse(s).left.map(_.getMessage)

  given JsonDecoder[ClientId] = JsonDecoder[String].map(ClientId(_))

  given JsonDecoder[Set[ScopeToken]] = JsonDecoder[String].map: scopeString =>
    scopeString.split(" ").map(ScopeToken(_)).toSet

  given JsonDecoder[Instant] = JsonDecoder[Long].map(Instant.ofEpochSecond)

  private given audienceDecoder: JsonDecoder[Vector[ClientId]] =
    JsonDecoder[ClientId].map(Vector(_))
      .orElse(JsonDecoder[Vector[String]].map(_.map(ClientId(_))))

  given JsonDecoder[AccessToken] = DeriveJsonDecoder.gen[AccessToken]

  /**
   * Parse and validate a JWT access token
   *
   * Performs:
   * 1. JWT parsing
   * 2. Signature verification using JWK set
   * 3. Expiration check
   * 4. Claims extraction via ZIO JSON
   *
   * @param tokenString The JWT token string
   * @param jwkSet JWK set for signature verification
   * @return Validated JWT access token or error
   */
  def parseAndValidate(
      tokenString: String,
      jwkSet: JWKSet,
  ): IO[Error, AccessToken] =
    for
      now <- Clock.instant

      jwt <- ZIO.attempt(SignedJWT.parse(tokenString)).orElseFail(Error.NotJWT)

      _ <- ZIO.attempt(
        Option(jwkSet.getKeyByKeyId(jwt.getHeader.getKeyID))
          .exists:
            case key: RSAKey => jwt.verify(RSASSAVerifier(key))
            case key: ECKey => jwt.verify(ECDSAVerifier(key))
            case key: OctetSequenceKey => jwt.verify(MACVerifier(key))
            case _ => false,
      )
        .orElseFail(Error.InvalidSignature)
        .filterOrFail(identity)(Error.InvalidSignature)

      jsonClaims = claimsToJson(jwt.getJWTClaimsSet)
      accessToken <- ZIO.fromEither(jsonClaims.as[AccessToken])
        .orElseFail(Error.InvalidClaims)

      _ <- ZIO.fail(Error.Expired)
        .when(accessToken.expiresAt.isBefore(now))
      
    yield accessToken
    

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
