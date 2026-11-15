package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.central.audit.CentralAudit.{CertFailure, CertReasons}
import versola.central.audit.{CentralAudit, DenialThrottle}
import versola.central.configuration.clients.certificates.{ClientCertificateIssuer, ClientCertificateRequests}
import versola.central.configuration.partners.{PartnerRegistry, PartnerStatus}
import versola.util.audit.{AuditEvents, AuditKind, AuditOutcome, CertAuditDetails, CertOperation}
import versola.util.http.Observability
import versola.util.{Base64Url, CertificateSubject, EdgeIdentityNames, EnrollmentToken, SecureRandom}
import zio.*
import zio.json.ast.Json

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import java.time.Instant
import javax.security.auth.x500.X500Principal

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
  /** A token for `edgeId` to enrol with (the unused tokens issued before it are void), valid for `validity` (an hour by default, at most
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
  ] = ZLayer.fromZIO:
    for
      edges <- ZIO.service[EdgeService]
      tokens <- ZIO.service[EdgeEnrollmentTokenRepository]
      issued <- ZIO.service[EdgeCertificateRepository]
      issuer <- ZIO.service[EdgeCertificateIssuer]
      random <- ZIO.service[SecureRandom]
      config <- ZIO.service[CentralConfig]
      events <- ZIO.service[AuditEvents]
      partners <- ZIO.service[PartnerRegistry]
      throttle <- DenialThrottle.perMinute
    yield Impl(edges, tokens, issued, issuer, random, config, events, partners, throttle)

  class Impl(
      edges: EdgeService,
      tokens: EdgeEnrollmentTokenRepository,
      issued: EdgeCertificateRepository,
      issuer: EdgeCertificateIssuer,
      random: SecureRandom,
      config: CentralConfig,
      events: AuditEvents,
      partners: PartnerRegistry,
      throttle: DenialThrottle,
  ) extends EdgeIdentityService:
    private def certificateEvent(
        kind: AuditKind,
        edgeId: EdgeId,
        outcome: AuditOutcome,
        reason: Option[String],
        details: CertAuditDetails,
    ): UIO[Unit] =
      CentralAudit.certificate(
        events,
        kind,
        CentralAudit.Actors.Edge,
        Some(edgeId.toString),
        ("edge", edgeId.toString),
        outcome,
        reason,
        details,
      )

    /** The event of an attempt that gave no certificate: the reason as a code, the subject the edge would have had, and no serial.
      *
      * A token that is not good is the one refusal anybody can cause, as the route has no credential before it: it is recorded only for
      * an edge that is registered, and once a minute for each, so that a flood of guesses is one row and not a row each. */
    private def failed(kind: AuditKind, operation: CertOperation, edgeId: EdgeId, failure: CertFailure[Any]): UIO[Unit] =
      val record = certificateEvent(
        kind,
        edgeId,
        failure.outcome,
        Some(failure.reason),
        CertAuditDetails.refused(operation = operation, clientId = None, subject = failure.subject),
      )
      if failure.reason != CertReasons.TokenInvalid then record
      else
        edges.find(edgeId).map(_.isDefined).flatMap: registered =>
          // the key is admitted only for an edge that exists, so that what the throttle keeps is bounded by the registered ones
          ZIO.when(registered)(throttle.admit(edgeId.toString)).flatMap(admitted => record.when(admitted.contains(true)).unit)

    /** What the CA signed: the chain handed to the edge and its leaf, which the event describes. */
    private case class Signed(chain: String, leaf: X509Certificate)

    /** A refusal of the request is a `denied` of `reason`; anything else that went wrong is a fault nothing classified. */
    private def refusing(reason: String, subject: Option[X500Principal])(error: Refused | Throwable): CertFailure[Refused | Throwable] =
      error match
        case refused: Refused => CertFailure(AuditOutcome.denied, reason, refused, subject)
        case other => CertFailure.unexpected(other)

    override def issueEnrollmentToken(edgeId: EdgeId, validity: Duration): IO[Refused | Throwable, IssuedToken] =
      for
        _ <- ZIO.fail(Refused(s"a token is valid for at most ${MaxTokenValidity.toHours} hours")).when(validity > MaxTokenValidity)
        _ <- ZIO.fail(Refused("a token must be valid for a positive time")).when(validity <= Duration.Zero)
        _ <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        secret <- random.nextBytes(32).map(Base64Url.encode)
        token = config.publicUrls.fold(secret.toString)(urls => EnrollmentToken.encode(urls.enroll.encode, secret))
        now <- Clock.instant
        expiresAt = now.plus(validity)
        // an edge has one live token: the ones issued before and not used are void from here on, so that a token that was copied
        // somewhere, or printed and forgotten, does not stay good beside the new one
        _ <- tokens.revoke(edgeId)
        _ <- tokens.create(hash(token), edgeId, expiresAt)
      yield IssuedToken(token, expiresAt)

    override def enroll(
        edgeId: Option[EdgeId],
        token: String,
        csrPem: String,
        signingKey: Option[Json.Obj],
    ): IO[Refused | InvalidToken.type | PartnerSuspended.type | Throwable, Identity] =
      // The edge an attempt is recorded for: the one named, and the token's own once the token is known good. A bad token that names no
      // edge belongs to none and is not recorded.
      Ref.make(edgeId).flatMap: attributed =>
        enrolling(edgeId, token, csrPem, signingKey, attributed)
          .tapError(failure => attributed.get.flatMap(ZIO.foreachDiscard(_)(failed(CentralAudit.CertEnrolled, CertOperation.Enroll, _, failure))))
          .mapError(_.error)

    private def enrolling(
        edgeId: Option[EdgeId],
        token: String,
        csrPem: String,
        signingKey: Option[Json.Obj],
        attributed: Ref[Option[EdgeId]],
    ): IO[CertFailure[Refused | InvalidToken.type | PartnerSuspended.type | Throwable], Identity] =
      def invalidToken = CertFailure(AuditOutcome.denied, CertReasons.TokenInvalid, InvalidToken, None)
      for
        now <- Clock.instant
        // Nothing is read or parsed on the strength of a token before the token is known good: the request comes from
        // someone not yet authenticated, so an unregistered edge, a registered one and a bad token are one and the
        // same answer, and a garbage request costs a hash and a lookup, not a parse.
        owner <- tokens.peek(hash(token), now).mapError(CertFailure.unexpected).someOrFail(invalidToken)
        // a token for another edge than the one named is no token
        _ <- ZIO.fail(invalidToken).unless(edgeId.forall(_ == owner))
        _ <- attributed.set(Some(owner))
        edge <- edges.find(owner).someOrFail(invalidToken)
        subject = Some(subjectFor(edge).principal)
        // the token is good, so this may be told: a suspended partner's edge is given no certificate (its unused tokens are also
        // revoked at suspension, which this covers for one issued while the suspension was being made)
        _ <- ZIO.when(edge.groupKind == EdgeGroupKind.Partner):
          partners.findByGroup(edge.groupId).flatMap: partner =>
            ZIO.fail(CertFailure(AuditOutcome.denied, CertReasons.Forbidden, PartnerSuspended, subject))
              .when(partner.exists(_.status == PartnerStatus.Suspended))
        // Authenticated from here on, so a mistake in the request may be told: and it is checked before the token is
        // spent, so it does not cost the operator a token.
        key <- enrollmentKey(edge, signingKey).mapError(refusing(CertReasons.BadRequest, subject))
        // An edge enrolling with the token alone does not know its own id or group, so its request cannot name them: central then
        // checks only that the request is signed by the key it names, and the certificate carries the names central chose. An edge
        // that names itself is held to naming exactly what it is.
        tokenResolved = edgeId.isEmpty
        _ <- ZIO.fromEither(
          if tokenResolved then CertificateSubject.provesPossession(csrPem) else CertificateSubject.matches(csrPem, subjectFor(edge)),
        ).mapError(reason => CertFailure(AuditOutcome.denied, CertReasons.RequestMismatch, Refused(reason), subject))
        spent <- tokens.consume(hash(token), now).mapError(CertFailure.unexpected)
        // Another caller spent it between the look and now.
        _ <- ZIO.fail(invalidToken).unless(spent.contains(edge.id))
        signed <- sign(edge, csrPem, namedByCentral = tokenResolved)
        // after the certificate, so that a CA that is down costs the token (as it always did) and not the key: the edge asks again with
        // a new token and the same key
        _ <- ZIO.foreachDiscard(key): jwk =>
          edges.setSigningKey(edge.id, jwk).mapError(CertFailure.unexpected).flatMap: set =>
            ZIO.fail(CertFailure(AuditOutcome.denied, CertReasons.BadRequest, Refused("the edge has a signing key already"), subject)).unless(set)
        _ <- certificateEvent(
          CentralAudit.CertEnrolled,
          edge.id,
          AuditOutcome.success,
          None,
          CertAuditDetails.of(CertOperation.Enroll, None, signed.leaf),
        )
      yield Identity(signed.chain, edge)

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
      renewing(edgeId, csrPem, presentedSerial, signingKey)
        .tapError(failure => failed(CentralAudit.CertRenewed, CertOperation.Renew, edgeId, failure))
        .mapError(_.error)

    private def renewing(
        edgeId: EdgeId,
        csrPem: String,
        presentedSerial: Option[String],
        signingKey: Option[Json.Obj],
    ): IO[CertFailure[Refused | Throwable], Identity] =
      for
        edge <- edges.find(edgeId)
          .someOrFail(CertFailure(AuditOutcome.denied, CertReasons.EdgeUnknown, Refused(s"edge '$edgeId' is not registered"), None))
        subject = Some(subjectFor(edge).principal)
        key <- ZIO.foreach(signingKey)(checkedKey(edgeId, _)).mapError(refusing(CertReasons.BadRequest, subject))
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge)))
          .mapError(reason => CertFailure(AuditOutcome.denied, CertReasons.RequestMismatch, Refused(reason), subject))
        _ <- ZIO.logInfo(s"renewing the certificate of edge '$edgeId' presented as serial ${presentedSerial.getOrElse("unknown")}")
        signed <- sign(edge, csrPem)
        // the key is replaced once there is a certificate to go with it
        _ <- ZIO.foreachDiscard(key)(jwk => edges.replaceSigningKey(edgeId, jwk)).mapError(CertFailure.unexpected)
        _ <- certificateEvent(
          CentralAudit.CertRenewed,
          edgeId,
          AuditOutcome.success,
          None,
          CertAuditDetails.of(CertOperation.Renew, None, signed.leaf),
        )
      yield Identity(signed.chain, edge)

    private def sign(edge: EdgeRecord, csrPem: String, namedByCentral: Boolean = false): IO[CertFailure[Refused | Throwable], Signed] =
      val subject = subjectFor(edge)
      val principal = Some(subject.principal)
      for
        chain <- issuer.issuer.sign(
          ClientCertificateRequests.signingRequest(csrPem, subject, EdgeCertificateIssuer.validity(config, edge.groupKind), namedByCentral),
        ).mapError: error =>
          CertFailure(AuditOutcome.failure, ClientCertificateIssuer.reasonOf(error), error, principal)
        leaf <- ZIO.attempt(
          CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(chain.getBytes(StandardCharsets.UTF_8)))
            .iterator.next.asInstanceOf[X509Certificate],
        ).mapError(error => CertFailure(AuditOutcome.failure, CertReasons.IssuerInvalidAnswer, error, principal))
        // What the CA returned must name exactly this edge and its group, or it would be refused on the listener
        // (or worse, accepted for something else): fail here rather than hand the edge a certificate it cannot use.
        _ <- ZIO.fail(CertFailure(
          AuditOutcome.failure,
          CertReasons.CertificateRejected,
          Refused("the issued certificate does not carry exactly the edge's names"),
          principal,
        )).unless(EdgeCertificate.uriNames(leaf).toSet == subject.uris.toSet)
        now <- Clock.instant
        _ <- issued.record(edge.id, leaf.getSerialNumber.toString(16), leaf.getNotAfter.toInstant, now)
          .catchAll(error => Observability.setError("edge_certificate_not_recorded", Some(s"edge '${edge.id}': $error")))
        _ <- ZIO.logInfo(
          s"issued certificate ${leaf.getSerialNumber.toString(16)} to edge '${edge.id}' (group '${edge.groupId}'), valid until ${leaf.getNotAfter.toInstant}",
        )
      yield Signed(chain, leaf)

    private def hash(token: String): String =
      MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
