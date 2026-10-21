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
  * Central issues it, through the CA behind [[EdgeCertificateIssuer]], and never holds the key: the edge generates
  * the key and sends a request, which central checks says exactly those two names and nothing else, so the names an
  * edge ends up with are central's decision and not the request's. The first certificate is traded for a one-time
  * token an operator created ([[issueEnrollmentToken]], [[enroll]]); every later one is asked for over the mutual-TLS
  * listener with the certificate the edge already has ([[renew]]), with a new key each time.
  *
  * The certificates an edge goes through are a chain, which begins at its enrollment. `edge-identity.max-age` is how old
  * a chain may get; at that age central extends it itself (`auto-extend`, the default, so an edge never stops for want
  * of anyone enrolling it again) or, if the operator turned that off, stops renewing until the edge is extended
  * ([[extend]]) or enrolled again. The operator's own (first-party) edges are always extended; turning it off is for
  * the edges of partners. Either way the edge is told the date the chain ends long before it does, in the
  * answer to every enrollment and renewal.
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

  /** The edge's first certificate, for the token it was given. Begins its chain. */
  def enroll(
      edgeId: EdgeId,
      token: String,
      csrPem: String,
  ): IO[EdgeIdentityService.Refused | EdgeIdentityService.InvalidToken.type | Throwable, EdgeIdentityService.Identity]

  /** A new certificate for an edge that proved who it is on the mutual-TLS listener. `presentedSerial` is the
    * certificate it proved it with, for the log. Refused when the chain has reached `edge-identity.max-age` and
    * `auto-extend` is off. */
  def renew(
      edgeId: EdgeId,
      csrPem: String,
      presentedSerial: Option[String] = None,
  ): IO[EdgeIdentityService.Refused | Throwable, EdgeIdentityService.Identity]

  /** Starts the edge's chain over, so that its next renewals are not refused for age. For an operator who turned
    * `auto-extend` off. */
  def extend(edgeId: EdgeId): IO[EdgeIdentityService.Refused | Throwable, Unit]

  /** When the chain of each edge ends, for the edges whose chain does: a partner's, where `auto-extend` is off. */
  def chainEnds: Task[Map[EdgeId, Instant]]

object EdgeIdentityService:
  val TokenValidity: Duration = 1.hour

  val MaxTokenValidity: Duration = 24.hours

  case class IssuedToken(token: String, expiresAt: Instant)

  /** An issued certificate chain (leaf first) and, if the chain of the edge ends, when. */
  case class Identity(chain: String, chainEndsAt: Option[Instant])

  /** Central will not do what was asked: an unknown edge, a request that says something other than the edge's names.
    * A refusal rather than a fault -- retrying does not change it. */
  case class Refused(reason: String) extends RuntimeException(reason)

  /** The token is unknown, already spent, expired or for another edge; from outside these are not told apart. */
  case object InvalidToken extends RuntimeException("the token is not valid")

  /** What the certificate of `edge` must say. */
  def subjectFor(edge: EdgeRecord): CertificateSubject =
    EdgeIdentityNames.subject(edgeId = edge.id, groupId = edge.groupId)

  val live: URLayer[
    EdgeService & EdgeEnrollmentTokenRepository & EdgeCertificateRepository & EdgeIdentityChainRepository &
      EdgeCertificateIssuer & SecureRandom & CentralConfig,
    EdgeIdentityService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  class Impl(
      edges: EdgeService,
      tokens: EdgeEnrollmentTokenRepository,
      issued: EdgeCertificateRepository,
      chains: EdgeIdentityChainRepository,
      issuer: EdgeCertificateIssuer,
      random: SecureRandom,
      config: CentralConfig,
  ) extends EdgeIdentityService:
    private val validity: Duration = EdgeCertificateIssuer.validity(config)
    private val policy = config.edgeIdentity
    private val maxAge: JavaDuration = JavaDuration.ofMillis(policy.maxAge.toMillis)

    /** Whether the chain of `edge` is extended by central itself. Always for the operator's own edges: they are not
      * someone whose approval is to be renewed, so `auto-extend = false` is about a partner's edges only. */
    private def extendsItself(edge: EdgeRecord): Boolean = policy.autoExtend || edge.groupKind == EdgeGroupKind.FirstParty

    /** The end of a chain that began at `started`, where chains end. */
    private def endOf(edge: EdgeRecord, started: Instant): Option[Instant] =
      Option.unless(extendsItself(edge))(started.plus(maxAge))

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

    override def enroll(edgeId: EdgeId, token: String, csrPem: String): IO[Refused | InvalidToken.type | Throwable, Identity] =
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
        _ <- chains.start(edgeId, now)
      yield Identity(chain, endOf(edge, now))

    override def renew(edgeId: EdgeId, csrPem: String, presentedSerial: Option[String]): IO[Refused | Throwable, Identity] =
      for
        edge <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subjectFor(edge))).mapError(Refused(_))
        now <- Clock.instant
        recorded <- chains.startedAt(edgeId)
        // An edge enrolled before chains were kept has none: its chain begins now.
        started <- recorded.fold(chains.start(edgeId, now).as(now))(ZIO.succeed(_))
        aged = JavaDuration.between(started, now).compareTo(maxAge) > 0
        // At the end of a chain: extend it, or stop, as the operator chose. Never a surprise: stopping happens only when the
        // operator turned extending off, and the edge has been told the date in every answer since its enrollment.
        begun <-
          if !aged then ZIO.succeed(started)
          else if extendsItself(edge) then
            ZIO.logInfo(s"the certificate chain of edge '$edgeId' reached ${policy.maxAge.toDays} days; extended") *>
              chains.start(edgeId, now).as(now)
          else
            ZIO.fail(Refused(
              s"the certificate chain of edge '$edgeId' reached its limit (${policy.maxAge.toDays} days since it was enrolled or " +
                s"last extended): extend it with POST /configuration/edges/extend?edgeId=$edgeId, or enrol it again with a new token",
            ))
        _ <- ZIO.logInfo(s"renewing the certificate of edge '$edgeId' presented as serial ${presentedSerial.getOrElse("unknown")}")
        chain <- sign(edge, csrPem)
      yield Identity(chain, endOf(edge, begun))

    override def extend(edgeId: EdgeId): IO[Refused | Throwable, Unit] =
      for
        _ <- edges.find(edgeId).someOrFail(Refused(s"edge '$edgeId' is not registered"))
        now <- Clock.instant
        _ <- chains.start(edgeId, now)
        _ <- ZIO.logInfo(s"the certificate chain of edge '$edgeId' was extended")
      yield ()

    override def chainEnds: Task[Map[EdgeId, Instant]] =
      if policy.autoExtend then ZIO.succeed(Map.empty)
      else
        for
          started <- chains.all
          partners <- edges.getAllEdges.map(_.filter(_.groupKind == EdgeGroupKind.Partner).map(_.id).toSet)
        yield started.view.filterKeys(partners).mapValues(_.plus(maxAge)).toMap

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
