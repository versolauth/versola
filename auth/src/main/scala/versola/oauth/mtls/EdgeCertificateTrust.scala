package versola.oauth.mtls

import versola.oauth.client.model.TenantId
import versola.oauth.client.{CentralSyncTokenService, EdgeRegistrySyncClient}
import versola.util.SequentialLayers.*
import versola.util.{CacheSource, ConfigSnapshot, CoreConfig, EdgeIdentityNames, ReloadingCache}
import zio.http.Client
import zio.{Clock, Scope, UIO, ZIO, ZLayer}

import java.io.FileInputStream
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.Date
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Which certificates on auth's mutual-TLS listener are an edge's, rather than a client's, and
  * for which tenants that edge may vouch.
  *
  * An edge that fronts an API (`/resources`) has verified the holder of a token at its own boundary,
  * and calls `/userinfo` on the holder's behalf: it can neither forward the holder's DPoP proof nor
  * present the holder's certificate. Auth accepts that call without either check only from a
  * certificate this recognises, because the certificate is then the proof that an edge it trusts
  * did the checking.
  *
  * A certificate is an edge's when **all** of these hold:
  *  - it names exactly one edge (`urn:versola:edge:<id>`, the name central puts in every edge
  *    certificate it issues) and is an end-entity certificate allowed for client authentication;
  *  - it was signed by one of the certificates in `mutual-tls.edge-trusted-certificates`, which
  *    are certificate authorities and valid now. The listener's own trust anchors also vouch for
  *    clients' certificates, and an issuer of those can put any name in a certificate it signs, so
  *    the name alone proves nothing: the edge issuer is a separate, dedicated one;
  *  - central still knows the edge (`EdgeRegistrySyncClient`). A certificate outlives the edge it
  *    was issued to, and this is what lets deleting the edge in central cut it off at auth within
  *    one configuration refresh instead of at expiry. An edge central does not list is a client.
  *
  * The tenants central assigned the edge come with it, and the caller must check the token's
  * client against them.
  *
  * Absent `edge-trusted-certificates` no certificate is an edge's, `/userinfo` behaves as it did
  * and auth fetches nothing from central for this.
  */
trait EdgeCertificateTrust:
  def edgeOf(certificate: X509Certificate): UIO[Option[EdgeCertificateTrust.Edge]]

  /** Reloads the edges central lists now, instead of at the next refresh interval. For the
    * non-prod `/service/configuration/sync`, which exists so that a test need not wait one out. */
  def refresh: UIO[Unit]

object EdgeCertificateTrust:
  /** @param tenantIds the tenants central assigned this edge to; it may vouch for those only. */
  case class Edge(id: String, tenantIds: Set[TenantId])

  private val UriName = 6
  private val ClientAuthOid = "1.3.6.1.5.5.7.3.2"
  private val KeyCertSignBit = 5

  val live: ZLayer[Scope & CoreConfig & Client & ConfigSnapshot, Throwable, EdgeCertificateTrust] =
    ZLayer.fromZIO:
      ZIO.serviceWithZIO[CoreConfig]: config =>
        config.mutualTls.flatMap(_.edgeTrustedCertificates) match
          case None => ZIO.succeed(none)
          case Some(path) =>
            for
              issuers <- load(path)
              _ <- warnIfOneCaServesBothRoles(config.mutualTls.map(_.trustedCertificates), issuers)
              sync <- (CentralSyncTokenService.live >>> EdgeRegistrySyncClient.live).build
              registry <- ReloadingCache
                .make[Map[String, Set[TenantId]]](config.configurationCacheRefreshInterval, fromSnapshot = true)
                .provideSomeEnvironment[Scope](_.add[CacheSource[Map[String, Set[TenantId]]]](sync.get[EdgeRegistrySyncClient]))
            yield Impl(issuers, registry.get, sync.get[EdgeRegistrySyncClient].getAll.flatMap(registry.set).ignoreLogged)

  /** If the listener trusts nothing but the edge issuers, one CA serves edges and clients alike (docker-local),
    * and only the issuer of client certificates refusing `urn:versola:edge:` names keeps a client from passing for
    * an edge. Not detectable otherwise, so the deployment docs say the edge CA must be dedicated. */
  private def warnIfOneCaServesBothRoles(trusted: Option[String], issuers: List[X509Certificate]): ZIO[Any, Throwable, Unit] =
    ZIO.foreachDiscard(trusted): path =>
      load(path, requireAuthority = false).flatMap: all =>
        ZIO.logWarning(
          "mutual-tls.edge-trusted-certificates names the only certificates the listener trusts: one CA issues both edge " +
            "and client certificates, so only that issuer refusing urn:versola:edge: names in client certificates keeps a " +
            "client from passing for an edge. Use a dedicated edge CA.",
        ).when(all.forall(cert => issuers.exists(_.getEncoded.sameElements(cert.getEncoded))))
    .ignoreLogged

  /** No certificate is an edge's. */
  val none: EdgeCertificateTrust = new EdgeCertificateTrust:
    override def edgeOf(certificate: X509Certificate): UIO[Option[Edge]] = ZIO.none
    override def refresh: UIO[Unit] = ZIO.unit

  /** Every certificate has to be a certificate authority that may sign certificates, so that a leaf
    * (a client's certificate) listed here by mistake cannot act as an edge issuer. */
  private[mtls] def load(path: String, requireAuthority: Boolean = true): ZIO[Any, Throwable, List[X509Certificate]] =
    ZIO.attemptBlocking:
      val factory = CertificateFactory.getInstance("X.509")
      val certificates = Using.resource(FileInputStream(path)): stream =>
        factory.generateCertificates(stream).asScala.toList.collect { case x509: X509Certificate => x509 }
      if certificates.isEmpty then
        throw IllegalArgumentException(s"mutual-tls.edge-trusted-certificates ($path) holds no certificate")
      if requireAuthority then
        certificates.foreach: certificate =>
          val usage = Option(certificate.getKeyUsage)
          if certificate.getBasicConstraints < 0 || usage.exists(bits => !(bits.length > KeyCertSignBit && bits(KeyCertSignBit))) then
            throw IllegalArgumentException(
              s"mutual-tls.edge-trusted-certificates ($path) holds ${certificate.getSubjectX500Principal.getName}, " +
                "which is not a certificate authority allowed to sign certificates: it is a leaf or lacks keyCertSign",
            )
      certificates

  class Impl(
      issuers: List[X509Certificate],
      registry: UIO[Map[String, Set[TenantId]]],
      reload: UIO[Unit] = ZIO.unit,
  ) extends EdgeCertificateTrust:
    override def refresh: UIO[Unit] = reload

    override def edgeOf(certificate: X509Certificate): UIO[Option[Edge]] =
      edgeIdOf(certificate).filter(_ => isEndEntityForClientAuth(certificate)) match
        case None => ZIO.none
        case Some(edgeId) =>
          Clock.instant.flatMap: now =>
            if !issuers.exists(issuer => validAt(issuer, Date.from(now)) && signedBy(certificate, issuer)) then ZIO.none
            else registry.map(_.get(edgeId).map(Edge(edgeId, _)))

  /** The edge id the certificate names, when it names exactly one edge. A certificate naming
    * several would let its holder call as any of them, the rule central applies when it accepts
    * one (`EdgeCertificate.isIssuedTo`). */
  private[mtls] def edgeIdOf(certificate: X509Certificate): Option[String] =
    val names =
      Try(Option(certificate.getSubjectAlternativeNames).map(_.asScala.toList).getOrElse(Nil)).getOrElse(Nil)
        .collect:
          case entry if entry.size == 2 && entry.get(0) == Integer.valueOf(UriName) => String.valueOf(entry.get(1))
        .collect:
          case name if name.startsWith(EdgeIdentityNames.EdgePrefix) => name.stripPrefix(EdgeIdentityNames.EdgePrefix)
        .toSet
    names.headOption.filter(id => names.size == 1 && id.nonEmpty)

  /** A leaf, not an issuer in its own right (a CA certificate carrying an edge's name would let its
    * holder sign certificates for any edge), and, where it says what it is for, for client authentication. */
  private def isEndEntityForClientAuth(certificate: X509Certificate): Boolean =
    certificate.getBasicConstraints == -1 &&
      Try(Option(certificate.getExtendedKeyUsage)).toOption.exists(_.forall(_.contains(ClientAuthOid)))

  private def validAt(certificate: X509Certificate, at: Date): Boolean =
    Try(certificate.checkValidity(at)).isSuccess

  /** Whether `issuer` signed `certificate`: the signature verifies under its key and the names
    * agree, so a certificate that merely claims an issuer it was not signed by is not accepted. */
  private def signedBy(certificate: X509Certificate, issuer: X509Certificate): Boolean =
    certificate.getIssuerX500Principal == issuer.getSubjectX500Principal &&
      Try(certificate.verify(issuer.getPublicKey)).isSuccess
