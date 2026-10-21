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
import java.time.{Duration as JavaDuration, Instant}

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
  /** A token for `edgeId` to enrol with, valid for `validity` (an hour by default, at most
    * [[EdgeIdentityService.MaxTokenValidity]]: a bundle handed to someone on another continent may need longer than an
    * hour, and no token should wait for days) and for one use. The token is returned here and never again: only its
    * hash is kept. */
  def issueEnrollmentToken(
      edgeId: EdgeId,
      validity: Duration = EdgeIdentityService.TokenValidity,
  ): IO[EdgeIdentityService.Refused | Throwable, EdgeIdentityService.IssuedToken]

  /** The edge's first certificate chain, for the token it was given. */
  def enroll(
      edgeId: EdgeId,
      token: String,
      csrPem: String,
  ): IO[EdgeIdentityService.Refused | EdgeIdentityService.InvalidToken.type | Throwable, String]

  /** A new certificate chain for an edge that proved who it is on the mutual-TLS listener. `presentedSerial` is the
    * certificate it proved it with, for the log. Refused once the chain of renewals is older than
    * [[EdgeIdentityService.MaxChainAge]]: an identity is not good for ever on renewals alone, the operator enrols the edge
    * again with a new token. */
  def renew(edgeId: EdgeId, csrPem: String, presentedSerial: Option[String] = None): IO[EdgeIdentityService.Refused | Throwable, String]

object EdgeIdentityService:
  val TokenValidity: Duration = 1.hour

  val MaxTokenValidity: Duration = 24.hours

  /** How long an edge may renew its way from one enrollment before it has to enrol again. */
  val MaxChainAge: Duration = 365.days

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

    override def issueEnrollmentToken(edgeId: EdgeId, validity: Duration): IO[Refused | Throwable, IssuedToken] =
      for
        _ <- ZIO.fail(Refused(s"a token is valid for at most ${MaxTokenValidity.toHours} hours")).when(validity > MaxTokenValidity)
        _ <- ZIO.fail(Refused("a token must be valid for a positive time")).when(validity <= Duration.Zero)
        _ <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        token <- random.nextBytes(32).map(Base64Url.encode)
        now <- Clock.instant
        expiresAt = now.plus(validity)
        _ <- tokens.create(hash(token), edgeId, expiresAt)
      yield IssuedToken(token, expiresAt)

    override def enroll(edgeId: EdgeId, token: String, csrPem: String): IO[Refused | InvalidToken.type | Throwable, String] =
      for
        now <- Clock.instant
        // Nothing is read or parsed on the strength of a token before the token is known good: the request comes from
        // someone not yet authenticated, so an unregistered edge, a registered one and a bad token are one and the
        // same answer, and a garbage request costs a hash and a lookup, not a parse.
        owner <- tokens.peek(hash(token), now)
        _ <- ZIO.fail(InvalidToken).unless(owner.contains(edgeId))
        edge <- edges.find(edgeId).someOrFail(InvalidToken)
        // Authenticated from here on, so a mistake in the request may be told: and it is checked before the token is
        // spent, so it does not cost the operator a token.
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge))).mapError(Refused(_))
        spent <- tokens.consume(hash(token), now)
        // Another caller spent it between the look and now.
        _ <- ZIO.fail(InvalidToken).unless(spent.contains(edgeId))
        chain <- sign(edge, csrPem)
      yield chain

    override def renew(edgeId: EdgeId, csrPem: String, presentedSerial: Option[String]): IO[Refused | Throwable, String] =
      for
        edge <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge))).mapError(Refused(_))
        now <- Clock.instant
        started <- issued.firstIssuedAt(edgeId)
        _ <- ZIO.fail(
          Refused(s"the identity of edge '$edgeId' has been renewed for more than ${MaxChainAge.toDays} days: enrol it again with a new token"),
        )
          .when(started.exists(first => JavaDuration.between(first, now).compareTo(MaxChainAge) > 0))
        _ <- ZIO.logInfo(s"renewing the certificate of edge '$edgeId' presented as serial ${presentedSerial.getOrElse("unknown")}")
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
