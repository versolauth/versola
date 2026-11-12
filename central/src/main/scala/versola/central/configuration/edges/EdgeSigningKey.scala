package versola.central.configuration.edges

import com.nimbusds.jose.jwk.{Curve, ECKey, JWK, RSAKey}
import zio.json.EncoderOps
import zio.json.ast.Json

import scala.util.Try

/** The signing key an edge brings when it enrols or renews (#962): its **public** JWK. The edge made the key pair and keeps the
  * private half; what central is sent is checked here before it is trusted to verify the tokens the edge signs.
  */
object EdgeSigningKey:
  val MinRsaBits = 2048
  val MaxRsaBits = 8192
  val MaxKeyIdLength = 128

  /** `jwk` as it may be stored, or why not: a public RSA key of 2048..8192 bits or a public P-256 key, with a `kid` (the
    * token's `kid` header is how the key is found). Anything with a private member is refused outright, so a key pasted by
    * mistake never reaches the table. */
  def validate(jwk: Json.Obj): Either[String, Json.Obj] =
    Try(JWK.parse(jwk.toJson)).toEither.left.map(_ => "signingKey is not a JWK").flatMap: key =>
      for
        _ <- Either.cond(!key.isPrivate, (), "signingKey must be the public key only: it carries a private member")
        _ <- Option(key.getKeyID).filter(_.nonEmpty) match
          case None => Left("signingKey must have a kid")
          case Some(kid) => Either.cond(kid.length <= MaxKeyIdLength, (), s"signingKey.kid is longer than $MaxKeyIdLength characters")
        _ <- key match
          case rsa: RSAKey =>
            val bits = rsa.getModulus.decodeToBigInteger.bitLength
            Either.cond(bits >= MinRsaBits && bits <= MaxRsaBits, (), s"an RSA signingKey must be $MinRsaBits to $MaxRsaBits bits, not $bits")
          case ec: ECKey =>
            Either.cond(ec.getCurve == Curve.P_256, (), "an EC signingKey must be on P-256")
          case _ => Left("signingKey must be an RSA or an EC (P-256) key")
      yield jwk

  /** The RFC 7638 thumbprint, which says whether two JWKs are one key whatever their `kid` or member order. */
  def thumbprint(jwk: Json.Obj): Option[String] =
    Try(JWK.parse(jwk.toJson).computeThumbprint().toString).toOption
