package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.central.audit.CentralAudit
import versola.central.configuration.clients.certificates.ClientCertificateRequests
import versola.central.configuration.partners.{PartnerRegistry, PartnerStatus}
import versola.util.audit.{AuditEvents, AuditKind}
import versola.util.http.Observability
import versola.util.{Base64Url, CertificateSubject, EdgeIdentityNames, EnrollmentToken, SecureRandom}
import zio.*
import zio.json.ast.Json

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import java.time.Instant

/** What an edge is recognised by, on central's mutual-TLS listener and at auth: a certificate that names it
  * (`urn:versola:edge:<id>`) and the group it belongs to (`urn:versola:edge-group:<group>`).
  *
  * Central issues it, through the CA behind [[EdgeCertificateIssuer]], and never holds the key: the edge generates
  * the key and sends a request, which central checks says exactly those two names and nothing else, so the names an
  * edge ends up with are central's decision and not the request's. The first certificate is traded for a one-time
  * token an operator created ([[issueEnrollmentToken]], [[enroll]]); every later one is asked for over the mutual-TLS
  * listener with the certificate the edge already has ([[renew]]), with a new key each time.
  *
  * Renewal is always on, for the operator's own edges and for a partner's alike (#549 decision 8): an edge that is
  * registered renews for as long as it is, and the way to stop a partner's edge is to suspend the partner, which
  * refuses it everything, renewal included. A partner's edge gets a shorter certificate
  * (`edge-certificates.partner-validity`, [[EdgeCertificateIssuer.validity]]), so that a suspension is followed by
  * expiry within days.
  */
trait EdgeIdentityService:
  /** A token for `edgeId` to enrol with, valid for `validity` (an hour by default, at most
    * [[EdgeIdentityService.MaxTokenValidity]]: a token handed to someone on another continent may need longer than an
    * hour, and no token should wait for days) and for one use. The token is returned here and never again: only its
    * hash is kept. It is `vtk1.<base64url(enrollUrl)>.<secret>` ([[EnrollmentToken]]) where central has a public enrollment
    * address (`central.public-urls.enroll`), so that the edge needs nothing else to find central; where it has none, the bare
    * secret, as before. */
  def issueEnrollmentToken(
      edgeId: EdgeId,
      validity: Duration = EdgeIdentityService.TokenValidity,
  ): IO[EdgeIdentityService.Refused | Throwable, EdgeIdentityService.IssuedToken]

  /** The edge's first certificate, for the token it was given, and the signing key it registers with it. The edge is the token's:
    * `edgeId`, when given (first-party edges name theirs), must be that edge, and the token alone says which when it is not.
    * `signingKey` is the edge's public JWK: required for an edge registered without a key (a partner's), and refused for one that
    * has a key already. */
  def enroll(
      edgeId: Option[EdgeId],
      token: String,
      csrPem: String,
      signingKey: Option[Json.Obj] = None,
  ): IO[
    EdgeIdentityService.Refused | EdgeIdentityService.InvalidToken.type | EdgeIdentityService.PartnerSuspended.type | Throwable,
    EdgeIdentityService.Identity,
  ]

  /** A new certificate for an edge that proved who it is on the mutual-TLS listener. `presentedSerial` is the
    * certificate it proved it with, for the log. `signingKey` is a new public JWK for the edge: when given, it replaces the
    * edge's key once the certificate is signed (the edge switches after it has installed the certificate). */
  def renew(
      edgeId: EdgeId,
      csrPem: String,
      presentedSerial: Option[String] = None,
      signingKey: Option[Json.Obj] = None,
  ): IO[EdgeIdentityService.Refused | Throwable, EdgeIdentityService.Identity]

object EdgeIdentityService:
  val TokenValidity: Duration = 1.hour

  val MaxTokenValidity: Duration = 24.hours

  case class IssuedToken(token: String, expiresAt: Instant)

  /** An issued certificate chain, leaf first, and the edge it was issued to. */
  case class Identity(chain: String, edge: EdgeRecord)

  /** Central will not do what was asked: an unknown edge, a request that says something other than the edge's names.
    * A refusal rather than a fault -- retrying does not change it. */
  case class Refused(reason: String) extends RuntimeException(reason)

  /** The token is unknown, already spent, expired or for another edge; from outside these are not told apart. */
  case object InvalidToken extends RuntimeException("the token is not valid")

  /** The edge belongs to a partner that is suspended (#962): it is given no certificate, whatever token it holds. */
  case object PartnerSuspended extends RuntimeException("the partner is suspended")

  /** What the certificate of `edge` must say. */
  def subjectFor(edge: EdgeRecord): CertificateSubject =
    EdgeIdentityNames.subject(edgeId = edge.id, groupId = edge.groupId)

  val live: URLayer[
    EdgeService & EdgeEnrollmentTokenRepository & EdgeCertificateRepository & EdgeCertificateIssuer & SecureRandom & CentralConfig &
      AuditEvents & PartnerRegistry,
    EdgeIdentityService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _))

  class Impl(
      edges: EdgeService,
      tokens: EdgeEnrollmentTokenRepository,
      issued: EdgeCertificateRepository,
      issuer: EdgeCertificateIssuer,
      random: SecureRandom,
      config: CentralConfig,
      events: AuditEvents,
      partners: PartnerRegistry,
  ) extends EdgeIdentityService:
    private def certificateEvent(kind: AuditKind, edgeId: EdgeId): UIO[Unit] =
      CentralAudit.certificate(events, kind, CentralAudit.Actors.Edge, Some(edgeId.toString), ("edge", edgeId.toString))

    override def issueEnrollmentToken(edgeId: EdgeId, validity: Duration): IO[Refused | Throwable, IssuedToken] =
      for
        _ <- ZIO.fail(Refused(s"a token is valid for at most ${MaxTokenValidity.toHours} hours")).when(validity > MaxTokenValidity)
        _ <- ZIO.fail(Refused("a token must be valid for a positive time")).when(validity <= Duration.Zero)
        _ <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        secret <- random.nextBytes(32).map(Base64Url.encode)
        token = config.publicUrls.fold(secret.toString)(urls => EnrollmentToken.encode(urls.enroll.encode, secret))
        now <- Clock.instant
        expiresAt = now.plus(validity)
        _ <- tokens.create(hash(token), edgeId, expiresAt)
      yield IssuedToken(token, expiresAt)

    override def enroll(
        edgeId: Option[EdgeId],
        token: String,
        csrPem: String,
        signingKey: Option[Json.Obj],
    ): IO[Refused | InvalidToken.type | PartnerSuspended.type | Throwable, Identity] =
      for
        now <- Clock.instant
        // Nothing is read or parsed on the strength of a token before the token is known good: the request comes from
        // someone not yet authenticated, so an unregistered edge, a registered one and a bad token are one and the
        // same answer, and a garbage request costs a hash and a lookup, not a parse.
        owner <- tokens.peek(hash(token), now).someOrFail(InvalidToken)
        // a token for another edge than the one named is no token
        _ <- ZIO.fail(InvalidToken).unless(edgeId.forall(_ == owner))
        edge <- edges.find(owner).someOrFail(InvalidToken)
        // the token is good, so this may be told: a suspended partner's edge is given no certificate (its unused tokens are also
        // revoked at suspension, which this covers for one issued while the suspension was being made)
        _ <- ZIO.when(edge.groupKind == EdgeGroupKind.Partner):
          partners.findByGroup(edge.groupId).flatMap(partner => ZIO.fail(PartnerSuspended).when(partner.exists(_.status == PartnerStatus.Suspended)))
        // Authenticated from here on, so a mistake in the request may be told: and it is checked before the token is
        // spent, so it does not cost the operator a token.
        key <- enrollmentKey(edge, signingKey)
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge))).mapError(Refused(_))
        spent <- tokens.consume(hash(token), now)
        // Another caller spent it between the look and now.
        _ <- ZIO.fail(InvalidToken).unless(spent.contains(edge.id))
        chain <- sign(edge, csrPem)
        // after the certificate, so that a CA that is down costs the token (as it always did) and not the key: the edge asks again with
        // a new token and the same key
        _ <- ZIO.foreachDiscard(key): jwk =>
          edges.setSigningKey(edge.id, jwk).flatMap(set => ZIO.fail(Refused("the edge has a signing key already")).unless(set))
        _ <- certificateEvent(CentralAudit.CertEnrolled, edge.id)
      yield Identity(chain, edge)

    /** The key an enrolment registers: none for an edge that has one (and none sent), the edge's own for one that has none. */
    private def enrollmentKey(edge: EdgeRecord, signingKey: Option[Json.Obj]): IO[Refused | Throwable, Option[Json.Obj]] =
      (edge.publicKey, signingKey) match
        case (Some(_), Some(_)) => ZIO.fail(Refused("the edge has a signing key already"))
        case (Some(_), None) => ZIO.none
        case (None, None) => ZIO.fail(Refused("signingKey is required: the public JWK of the key this edge signs with"))
        case (None, Some(jwk)) => checkedKey(edge.id, jwk).asSome

    /** `jwk` if it is a public key central may trust for `edgeId` and no other edge has it. */
    private def checkedKey(edgeId: EdgeId, jwk: Json.Obj): IO[Refused | Throwable, Json.Obj] =
      for
        valid <- ZIO.fromEither(EdgeSigningKey.validate(jwk)).mapError(Refused(_))
        others <- edges.getAllEdges.map(_.filter(_.id != edgeId))
        mine = EdgeSigningKey.thumbprint(valid)
        _ <- ZIO.fail(Refused("the signing key is registered for another edge"))
          .when(others.exists(other =>
            (other.publicKey.toVector ++ other.oldPublicKey.toVector).exists(known => EdgeSigningKey.thumbprint(known) == mine),
          ))
      yield valid

    override def renew(
        edgeId: EdgeId,
        csrPem: String,
        presentedSerial: Option[String],
        signingKey: Option[Json.Obj],
    ): IO[Refused | Throwable, Identity] =
      for
        edge <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        key <- ZIO.foreach(signingKey)(checkedKey(edgeId, _))
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge))).mapError(Refused(_))
        _ <- ZIO.logInfo(s"renewing the certificate of edge '$edgeId' presented as serial ${presentedSerial.getOrElse("unknown")}")
        chain <- sign(edge, csrPem)
        // the key is replaced once there is a certificate to go with it
        _ <- ZIO.foreachDiscard(key)(jwk => edges.replaceSigningKey(edgeId, jwk))
        _ <- certificateEvent(CentralAudit.CertRenewed, edgeId)
      yield Identity(chain, edge)

    private def sign(edge: EdgeRecord, csrPem: String): IO[Refused | Throwable, String] =
      val subject = subjectFor(edge)
      for
        chain <- issuer.issuer.sign(
          ClientCertificateRequests.signingRequest(csrPem, subject, EdgeCertificateIssuer.validity(config, edge.groupKind)),
        )
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
