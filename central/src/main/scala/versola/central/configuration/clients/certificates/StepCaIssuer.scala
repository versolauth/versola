package versola.central.configuration.clients.certificates

import com.nimbusds.jose.crypto.factories.DefaultJWSSignerFactory
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.central.CentralConfig.StepCaConfig
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.{Date, UUID}

/** Signs through step-ca's `/1.0/sign`, authorised by a one-time token from a JWK provisioner
  * (https://smallstep.com/docs/step-ca/provisioners/#jwk).
  *
  * The token is what limits this: it names the one subject and the SANs the request may carry,
  * is signed with the provisioner's key, and lives for a minute -- step-ca refuses a request
  * whose CSR names anything else, and the provisioner's own claims bound the certificate's
  * lifetime. Central holds the provisioner key, which can only ask for what those allow; the
  * CA's own keys stay in step-ca.
  */
object StepCaIssuer:

  def make(config: StepCaConfig, client: Client): Task[ClientCertificateIssuer] =
    for
      root <- ZIO.attemptBlocking(Files.readString(Path.of(config.rootCertificate)))
      fingerprint <- ZIO.attempt(fingerprintOf(root))
      key <- ZIO.attemptBlocking(JWK.parse(Files.readString(Path.of(config.provisionerKey))))
      _ <- ZIO.fail(IllegalArgumentException("the step-ca provisioner key must be a private key")).unless(key.isPrivate)
      signer <- ZIO.attempt(DefaultJWSSignerFactory().createJWSSigner(key))
      ssl = ClientSSLConfig.FromCertFile(config.rootCertificate)
      base = config.url.encode.stripSuffix("/")
      audience = s"$base/1.0/sign"
    yield new ClientCertificateIssuer:
      override def sign(request: CertificateSigningRequest): Task[String] =
        for
          now <- Clock.instant
          token <- ZIO.attempt(oneTimeToken(config, key, signer, fingerprint, audience, request, now))
          url <- ZIO.fromEither(URL.decode(audience)).mapError(error => RuntimeException(error.getMessage))
          body = Json.Obj(
            "csr" -> Json.Str(request.csrPem),
            "ott" -> Json.Str(token),
            "notAfter" -> Json.Str(s"${request.validity.toSeconds}s"),
          ).toJson
          response <- ZIO.scoped:
            client.ssl(ssl).request(
              Request.post(url, Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json)),
            ).flatMap(r => r.body.asString.map(r.status -> _))
          (status, text) = response
          _ <- ZIO.fail(RuntimeException(s"step-ca refused the signing request with $status: $text")).unless(status.isSuccess)
          chain <- ZIO.fromEither(chainOf(text)).mapError(RuntimeException(_))
        yield chain

  private def oneTimeToken(
      config: StepCaConfig,
      key: JWK,
      signer: com.nimbusds.jose.JWSSigner,
      fingerprint: String,
      audience: String,
      request: CertificateSigningRequest,
      now: Instant,
  ): String =
    val sans = request.dnsNames ++ request.uris ++ request.emailAddresses ++ request.ipAddresses
    val claims = JWTClaimsSet.Builder()
      .issuer(config.provisioner)
      .subject(request.commonName)
      .audience(audience)
      .issueTime(Date.from(now))
      .notBeforeTime(Date.from(now.minusSeconds(30)))
      .expirationTime(Date.from(now.plusSeconds(60)))
      .jwtID(UUID.randomUUID().toString)
      .claim("sha", fingerprint)
      .claim("sans", java.util.List.of(sans*))
      .build()
    val algorithm = key.getAlgorithm match
      case null => if key.getKeyType.getValue == "EC" then JWSAlgorithm.ES256 else JWSAlgorithm.RS256
      case other => JWSAlgorithm.parse(other.getName)
    val jwt = SignedJWT(JWSHeader.Builder(algorithm).keyID(key.getKeyID).`type`(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims)
    jwt.sign(signer)
    jwt.serialize()

  /** step-ca's own definition: SHA-256 of the root certificate's DER, hex. */
  private def fingerprintOf(rootPem: String): String =
    val certificate = CertificateFactory.getInstance("X.509")
      .generateCertificate(ByteArrayInputStream(rootPem.getBytes(StandardCharsets.UTF_8)))
    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded).map(b => f"${b & 0xff}%02x").mkString

  /** `certChain` is the leaf and its intermediates; older responses carry `crt` and `ca`. */
  private def chainOf(body: String): Either[String, String] =
    body.fromJson[Json.Obj].left.map(error => s"step-ca's answer is not JSON: $error").flatMap: obj =>
      val chain = obj.get("certChain").flatMap(_.asArray).map(_.flatMap(_.asString).toList)
      chain.filter(_.nonEmpty) match
        case Some(certificates) => Right(certificates.map(_.trim).mkString("\n") + "\n")
        case None =>
          obj.get("crt").flatMap(_.asString) match
            case Some(leaf) => Right((leaf.trim :: obj.get("ca").flatMap(_.asString).map(_.trim).toList).mkString("\n") + "\n")
            case None => Left("step-ca's answer carries no certificate")
