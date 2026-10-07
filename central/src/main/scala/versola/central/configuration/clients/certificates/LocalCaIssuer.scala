package versola.central.configuration.clients.certificates

import org.bouncycastle.asn1.x509.{BasicConstraints, ExtendedKeyUsage, Extension, KeyPurposeId, KeyUsage}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509ExtensionUtils, JcaX509v3CertificateBuilder}
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.{JcaContentSignerBuilder, JcaContentVerifierProviderBuilder}
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import versola.central.CentralConfig.ClientCertificateAuthorityConfig
import versola.central.configuration.clients.ClientCertificateAuthority
import zio.*

import java.io.StringReader
import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.SecureRandom
import java.util.Date

/** Signs with a CA whose certificate and key central holds -- the `client-certificate-authority`
  * block (#451), kept so a deployment that already has one (the `local` stack's terminator CA)
  * keeps working, and gains the same renewal the other backends have.
  *
  * The one backend where central holds the CA key: prefer `step-ca` or `cert-manager`, which keep
  * it out of the application (see `develop.md`). The certificate is signed directly by that CA and
  * is a leaf only -- auth's mutual-TLS listener is handed no chain, so there is no intermediate it
  * could be built through.
  */
object LocalCaIssuer:

  /** Starts no earlier than this before issuance, so a party whose clock runs behind central's
    * does not refuse a certificate as not yet valid. */
  private val ClockSkew = 5.minutes

  def make(config: ClientCertificateAuthorityConfig): Task[ClientCertificateIssuer] =
    for
      certificatePem <- ZIO.attemptBlocking(Files.readString(Path.of(config.certificate)))
      privateKeyPem <- ZIO.attemptBlocking(Files.readString(Path.of(config.privateKey)))
      now <- Clock.instant
      // The checks the CA has always been held to at startup: a CA, unexpired, able to sign, and
      // the key its own.
      _ <- ZIO.fromEither(ClientCertificateAuthority.fromPem(
        certificatePem, privateKeyPem, config.validityDays.getOrElse(ClientCertificateAuthority.DefaultValidityDays), now,
      )).mapError(reason => IllegalArgumentException(s"client-certificate-authority: $reason"))
      certificate <- ZIO.fromEither(ClientCertificateAuthority.readCertificate(certificatePem)).mapError(IllegalArgumentException(_))
      key <- ZIO.fromEither(ClientCertificateAuthority.readPrivateKey(privateKeyPem)).mapError(IllegalArgumentException(_))
    yield new ClientCertificateIssuer:
      private val random = SecureRandom()
      private val caEnd = certificate.getNotAfter.toInstant
      private val signatureName = if key.getAlgorithm == "EC" then "SHA256withECDSA" else "SHA256withRSA"
      private val subjectKeyIdentifier = JcaX509ExtensionUtils().createAuthorityKeyIdentifier(certificate)

      override def sign(request: CertificateSigningRequest): Task[String] =
        Clock.instant.flatMap: now =>
          ZIO.fail(ClientCertificateAuthority.Expired(caEnd)).unless(caEnd.isAfter(now)) *>
            ZIO.attemptBlocking(signAt(request, now))

      private def signAt(request: CertificateSigningRequest, now: java.time.Instant): String =
        val csr = JcaPKCS10CertificationRequest(
          PEMParser(StringReader(request.csrPem)).readObject().asInstanceOf[PKCS10CertificationRequest],
        )
        // Proof the request's author holds the key it names.
        if !csr.isSignatureValid(JcaContentVerifierProviderBuilder().build(csr.getPublicKey)) then
          throw IllegalArgumentException("the request's signature does not verify against its own key")
        val requestedEnd = now.plusMillis(request.validity.toMillis)
        val notAfter = if requestedEnd.isAfter(caEnd) then caEnd else requestedEnd
        val builder = JcaX509v3CertificateBuilder(
          certificate,
          // RFC 5280 §4.1.2.2: positive, at most 20 octets, and unpredictable.
          BigInteger(159, random).setBit(158),
          Date.from(now.minusMillis(ClockSkew.toMillis)),
          Date.from(notAfter),
          csr.getSubject,
          csr.getPublicKey,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
        builder.addExtension(Extension.authorityKeyIdentifier, false, subjectKeyIdentifier)
        Option(csr.getRequestedExtensions).flatMap(e => Option(e.getExtension(Extension.subjectAlternativeName))).foreach: san =>
          builder.addExtension(Extension.subjectAlternativeName, false, san.getParsedValue)
        val signer = JcaContentSignerBuilder(signatureName).build(key)
        val issued = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        ClientCertificateRequests.pem("CERTIFICATE", issued.getEncoded)
