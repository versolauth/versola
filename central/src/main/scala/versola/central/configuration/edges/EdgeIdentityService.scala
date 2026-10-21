package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.central.configuration.clients.certificates.ClientCertificateRequests
import versola.util.http.Observability
import versola.util.{Base64Url, CertificateSubject, EdgeIdentityNames, SecureRandom}
import zio.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import java.time.Instant

/** What an edge is recognised by, on central's mutual-TLS listener and at auth: a certificate that names it
  * (`urn:versola:edge:<id>`) and the group it belongs to (`urn:versola:edge-group:<group>`).
  *
  * Central issues it, through the CA behind [[ClientCertificateIssuer]], and never holds the key: the edge generates
  * the key and sends a request, which central checks says exactly those two names and nothing else, so the names an
  * edge ends up with are central's decision and not the request's. The first certificate is traded for a one-time
  * token an operator created ([[issueEnrollmentToken]], [[enroll]]); every later one is asked for over the mutual-TLS
  * listener with the certificate the edge already has ([[renew]]), with a new key each time.
  */
trait EdgeIdentityService:
  /** A token for `edgeId` to enrol with, valid for [[EdgeIdentityService.TokenValidity]] and for one use. The token is
    * returned here and never again: only its hash is kept. */
  def issueEnrollmentToken(edgeId: EdgeId): IO[EdgeIdentityService.Refused | Throwable, EdgeIdentityService.IssuedToken]

  /** The edge's first certificate chain, for the token it was given. */
  def enroll(
      edgeId: EdgeId,
      token: String,
      csrPem: String,
  ): IO[EdgeIdentityService.Refused | EdgeIdentityService.InvalidToken.type | Throwable, String]

  /** A new certificate chain for an edge that proved who it is on the mutual-TLS listener. */
  def renew(edgeId: EdgeId, csrPem: String): IO[EdgeIdentityService.Refused | Throwable, String]

object EdgeIdentityService:
  val TokenValidity: Duration = 1.hour

  case class IssuedToken(token: String, expiresAt: Instant)

  /** Central will not do what was asked: an unknown edge, a request that says something other than the edge's names.
    * A refusal rather than a fault -- retrying does not change it. */
  case class Refused(reason: String) extends RuntimeException(reason)

  /** The token is unknown, already spent, expired or for another edge; from outside these are not told apart. */
  case object InvalidToken extends RuntimeException("the token is not valid")

  /** What the certificate of `edge` must say. */
  def subjectFor(edge: EdgeRecord): CertificateSubject =
    EdgeIdentityNames.subject(edgeId = edge.id, groupId = edge.groupId)

  val live: URLayer[
    EdgeService & EdgeEnrollmentTokenRepository & EdgeCertificateRepository & EdgeCertificateIssuer & SecureRandom &
      CentralConfig,
    EdgeIdentityService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  class Impl(
      edges: EdgeService,
      tokens: EdgeEnrollmentTokenRepository,
      issued: EdgeCertificateRepository,
      issuer: EdgeCertificateIssuer,
      random: SecureRandom,
      config: CentralConfig,
  ) extends EdgeIdentityService:
    private val validity: Duration = EdgeCertificateIssuer.validity(config)

    override def issueEnrollmentToken(edgeId: EdgeId): IO[Refused | Throwable, IssuedToken] =
      for
        _ <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        token <- random.nextBytes(32).map(Base64Url.encode)
        now <- Clock.instant
        expiresAt = now.plus(TokenValidity)
        _ <- tokens.create(hash(token), edgeId, expiresAt)
      yield IssuedToken(token, expiresAt)

    override def enroll(edgeId: EdgeId, token: String, csrPem: String): IO[Refused | InvalidToken.type | Throwable, String] =
      for
        edge <- edges.find(edgeId).someOrFail(InvalidToken)
        // The request is checked before the token is spent, so a mistake in it does not cost the operator a token.
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge))).mapError(Refused(_))
        now <- Clock.instant
        owner <- tokens.consume(hash(token), now)
        // Unknown, spent, expired, or for another edge look the same from outside.
        _ <- ZIO.fail(InvalidToken).unless(owner.contains(edgeId))
        chain <- sign(edge, csrPem)
      yield chain

    override def renew(edgeId: EdgeId, csrPem: String): IO[Refused | Throwable, String] =
      for
        edge <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge))).mapError(Refused(_))
        chain <- sign(edge, csrPem)
      yield chain

    private def sign(edge: EdgeRecord, csrPem: String): IO[Refused | Throwable, String] =
      val subject = subjectFor(edge)
      for
        chain <- issuer.issuer.sign(ClientCertificateRequests.signingRequest(csrPem, subject, validity))
        leaf <- ZIO.attempt:
          CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(chain.getBytes(StandardCharsets.UTF_8)))
            .iterator.next.asInstanceOf[X509Certificate]
        // What the CA returned must name exactly this edge and its group, or it would be refused on the listener
        // (or worse, accepted for something else): fail here rather than hand the edge a certificate it cannot use.
        _ <- ZIO.fail(Refused("the issued certificate does not carry exactly the edge's names"))
          .unless(EdgeCertificate.uriNames(leaf).toSet == subject.uris.toSet)
        now <- Clock.instant
        _ <- issued.record(edge.id, leaf.getSerialNumber.toString(16), leaf.getNotAfter.toInstant, now)
          .catchAll(error => Observability.setError("edge_certificate_not_recorded", Some(s"edge '${edge.id}': $error")))
        _ <- ZIO.logInfo(
          s"issued certificate ${leaf.getSerialNumber.toString(16)} to edge '${edge.id}' (group '${edge.groupId}'), valid until ${leaf.getNotAfter.toInstant}",
        )
      yield chain

    private def hash(token: String): String =
      MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
