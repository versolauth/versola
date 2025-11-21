package versola.http

import com.nimbusds.jwt.JWTClaimsSet
import zio.IO

trait JWTClaimDecoder[A]:
  self =>
  def decode(claims: JWTClaimsSet): IO[String, A]

  def attributes(claims: JWTClaimsSet): IO[String, Map[String, String]]

  def map[B](f: A => B): JWTClaimDecoder[B] =
    new JWTClaimDecoder[B]:
      override def decode(claims: JWTClaimsSet): IO[String, B] =
        self.decode(claims).map(f)

      override def attributes(claims: JWTClaimsSet): IO[String, Map[String, String]] =
        self.attributes(claims)
