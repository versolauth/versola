package versola.central.configuration.jwks

import com.nimbusds.jose.jwk.JWK
import versola.util.JWT
import zio.json.EncoderOps
import zio.json.ast.Json

import scala.util.Try

/** What central accepts as a published key, and what it publishes of one it holds.
  *
  * The JWKS is a public document: auth serves it to anyone and edge verifies tokens with it. A key supplied over the admin API
  * therefore has to be the public half and nothing else, and the document is built from a fixed list of members instead of
  * from whatever the stored JSON carries, so a row that held more (written by an older build) is not published with it.
  * Messages name members and rules, never a value.
  */
object JwkValidation:

  /** The members of a JWK (RFC 7517 §4, RFC 7518 §6) that hold private or secret key material. */
  val PrivateMembers: Set[String] = Set("d", "p", "q", "dp", "dq", "qi", "oth", "k")

  /** The members of a public key that are published: the parameters of the supported key types and the registered metadata. */
  val PublishedMembers: Set[String] =
    Set("kty", "kid", "use", "alg", "key_ops", "n", "e", "crv", "x", "y", "x5c", "x5t", "x5t#S256", "x5u")

  private val Uses = Set("sig", "enc")

  private val SignatureAlgorithms: Map[JWT.Algorithm, String] = Map(
    JWT.Algorithm.RS256 -> "RSA",
    JWT.Algorithm.PS256 -> "RSA",
    JWT.Algorithm.ES256 -> "EC",
  )

  /** `Left` with the reason when the key cannot be stored; nothing is written then. */
  def validate(jwk: Json.Obj): Either[String, Unit] =
    val members = jwk.fields.map(_._1).toSet
    val privateMembers = members.intersect(PrivateMembers)
    if privateMembers.nonEmpty then
      Left(s"JWK must be a public key: remove the private members (${privateMembers.toList.sorted.mkString(", ")})")
    else
      for
        kty <- text(jwk, "kty").toRight("JWK must contain a 'kty' field")
        _ <- requireMembers(jwk, kty)
        _ <- checkAlgorithm(jwk, kty)
        _ <- checkUse(jwk)
        _ <- Try(JWK.parse(jwk.toJson)).toEither.left.map(_ => "JWK is not a valid public key")
      yield ()

  /** The key as it is published: the members of [[PublishedMembers]] and no others. */
  def publicProjection(jwk: Json.Obj): Json.Obj =
    Json.Obj(jwk.fields.filter((name, _) => PublishedMembers.contains(name)))

  private def text(jwk: Json.Obj, name: String): Option[String] =
    jwk.get(name).collect { case Json.Str(value) if value.nonEmpty => value }

  private def requireMembers(jwk: Json.Obj, kty: String): Either[String, Unit] =
    val required = kty match
      case "RSA" => Right(List("n", "e"))
      case "EC" => Right(List("crv", "x", "y"))
      case _ => Left("JWK 'kty' must be RSA or EC")
    for
      names <- required
      missing = names.filter(name => text(jwk, name).isEmpty)
      _ <- Either.cond(missing.isEmpty, (), s"JWK of type $kty must contain ${missing.mkString(", ")}")
      // The only curve ES256 is defined over, and the only one auth and edge verify with.
      _ <- Either.cond(kty != "EC" || text(jwk, "crv").contains("P-256"), (), "JWK of type EC must be on the curve P-256")
    yield ()

  private def checkAlgorithm(jwk: Json.Obj, kty: String): Either[String, Unit] =
    jwk.get("alg") match
      case None => Right(())
      case Some(Json.Str(name)) =>
        JWT.Algorithm.fromName(name).flatMap(algorithm => SignatureAlgorithms.get(algorithm).map(algorithm -> _)) match
          case None => Left(s"JWK 'alg' must be one of ${SignatureAlgorithms.keys.mkString(", ")}")
          case Some((algorithm, keyType)) if keyType != kty => Left(s"JWK 'alg' $algorithm needs a key of type $keyType")
          case Some(_) => Right(())
      case Some(_) => Left(s"JWK 'alg' must be one of ${SignatureAlgorithms.keys.mkString(", ")}")

  private def checkUse(jwk: Json.Obj): Either[String, Unit] =
    jwk.get("use") match
      case None => Right(())
      case Some(Json.Str(value)) if Uses.contains(value) => Right(())
      case Some(_) => Left("JWK 'use' must be sig or enc")
