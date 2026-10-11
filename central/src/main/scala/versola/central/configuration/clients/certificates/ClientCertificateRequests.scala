package versola.central.configuration.clients.certificates

import versola.central.configuration.clients.{ClientId, MutualTlsAuth, MutualTlsSubjectType}
import versola.central.configuration.tenants.TenantId
import versola.util.CertificateSubject
import zio.*

import javax.security.auth.x500.X500Principal

/** What an edge-fronted client's certificate must say, decided from the client's own `mtlsAuth`
  * registration -- the thing auth later compares the presented certificate against (RFC 8705
  * §2.1.2): the registered subject value has to appear in the certificate, so the request is
  * built from it and not the other way round. Registration
  * (`InvalidRegistrationConfiguration.validateEdgeClientCertificate`) holds a certificate to the
  * same rule and refuses one that misses it.
  *
  * The key pair and the request themselves are made by whoever holds the key
  * ([[CertificateSubject.generate]]): central for a certificate it issues, an edge for one it
  * enrols.
  */
object ClientCertificateRequests:

  /** The request for `subject` with a freshly generated key, as the issuers are asked to sign it. */
  case class Generated(privateKeyPem: String, request: CertificateSigningRequest)

  /** What a registration that names no `mtlsAuth` is recognised by: `CN=<client>,OU=<tenant>,O=Versola`,
    * the subject #451's CA has always issued. Rendered the way auth renders a presented certificate's. */
  def defaultAuth(tenantId: TenantId, clientId: ClientId): MutualTlsAuth.TlsClientAuth =
    val dn = X500Principal(s"CN=${escape(clientId)},OU=${escape(tenantId)},O=Versola").getName(X500Principal.RFC2253)
    MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, dn)

  def subjectFor(clientId: ClientId, auth: MutualTlsAuth): Either[String, CertificateSubject] =
    auth match
      case MutualTlsAuth.SelfSignedTlsClientAuth() =>
        Left("self_signed_tls_client_auth has no subject to issue for -- its certificate is matched by key, not by name")
      case MutualTlsAuth.TlsClientAuth(subjectType, value) =>
        def own = X500Principal(s"CN=${escape(clientId)}").getName(X500Principal.RFC2253)
        subjectType match
          case MutualTlsSubjectType.subject_dn =>
            scala.util.Try(X500Principal(value)).toEither.left
              .map(error => s"mtlsAuth subject_dn '$value' is not an RFC 4514 distinguished name: ${error.getMessage}")
              .map(principal =>
                CertificateSubject(
                  distinguishedName = principal.getName(X500Principal.RFC2253),
                  commonName = CertificateSubject.commonNameOf(principal).getOrElse(clientId),
                  dnsNames = Nil,
                  uris = Nil,
                  emailAddresses = Nil,
                  ipAddresses = Nil,
                ),
              )
          case MutualTlsSubjectType.san_dns =>
            Right(CertificateSubject(own, clientId, dnsNames = List(value), uris = Nil, emailAddresses = Nil, ipAddresses = Nil))
          case MutualTlsSubjectType.san_uri =>
            Right(CertificateSubject(own, clientId, dnsNames = Nil, uris = List(value), emailAddresses = Nil, ipAddresses = Nil))
          case MutualTlsSubjectType.san_email =>
            Right(CertificateSubject(own, clientId, dnsNames = Nil, uris = Nil, emailAddresses = List(value), ipAddresses = Nil))
          case MutualTlsSubjectType.san_ip =>
            Right(CertificateSubject(own, clientId, dnsNames = Nil, uris = Nil, emailAddresses = Nil, ipAddresses = List(value)))

  def generate(subject: CertificateSubject, validity: Duration): Task[Generated] =
    CertificateSubject.generate(subject).map: generated =>
      Generated(generated.privateKeyPem, signingRequest(generated.csrPem, subject, validity))

  /** The issuer-facing form of a request for `subject`. */
  def signingRequest(csrPem: String, subject: CertificateSubject, validity: Duration): CertificateSigningRequest =
    CertificateSigningRequest(
      csrPem = csrPem,
      commonName = subject.commonName,
      dnsNames = subject.dnsNames,
      uris = subject.uris,
      emailAddresses = subject.emailAddresses,
      ipAddresses = subject.ipAddresses,
      validity = validity,
    )

  def pem(label: String, der: Array[Byte]): String = CertificateSubject.pem(label, der)

  private def escape(value: String): String =
    value.flatMap:
      case c if ",+\"\\<>;=".contains(c) => s"\\$c"
      case c => c.toString
