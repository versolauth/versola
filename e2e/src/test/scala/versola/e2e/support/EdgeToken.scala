package versola.e2e.support

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.*

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.{Base64, Date, UUID}

/** What edge's `CentralSyncTokenService` presents to central: `RS256` with the key central minted at registration, naming the
  * edge in the `edge_id` header, and a `jti`, which central's verifier insists on. Built with Nimbus directly, since e2e has
  * no access to the services' own `JWT` helper. */
object EdgeToken:

  def sign(edgeId: String, keyId: String, privateKey: String): Task[String] =
    ZIO.attempt:
      val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getUrlDecoder.decode(privateKey)))
      val now = Instant.now()
      val claims = JWTClaimsSet.Builder()
        .issuer("edge")
        .subject("edge")
        .audience("central")
        .jwtID(UUID.randomUUID().toString)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(60)))
        .build()
      val header = JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(keyId)
        .`type`(JOSEObjectType.JWT)
        .customParam("edge_id", edgeId)
        .build()
      val jwt = SignedJWT(header, claims)
      jwt.sign(RSASSASigner(key))
      jwt.serialize()
