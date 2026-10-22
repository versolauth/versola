package versola.central.configuration.clients.est

import versola.central.CentralConfig
import versola.central.configuration.CreateClientRequest
import versola.central.configuration.clients.certificates.{ClientCertificateIssuer, ClientCertificateRequests}
import versola.central.configuration.clients.{
  ApplicationType,
  AuthMethod,
  ClientAlreadyExists,
  ClientId,
  InvalidRegistrationConfiguration,
  OAuthClientRecord,
  OAuthClientService,
  RegisteredClient,
}
import versola.central.configuration.edges.EdgeCertificate
import versola.util.http.Observability
import versola.util.{Base64Url, CertificateSubject, SecureRandom}
import zio.*
import zio.json.JsonCodec
import zio.metrics.{Metric, MetricLabel}
import zio.schema.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import java.time.{Duration as JavaDuration, Instant}

/** How a client that is registered with `estEnrollment` gets and renews its mTLS client certificate: the policy half of
  * EST (RFC 7030), which auth serves to the client and calls central for. Central alone holds the power to issue --
  * through the CA behind [[ClientCertificateIssuer]], never with the client's key. Auth is in the trust zone of the mutual-TLS
  * terminator, not outside it: it is what tells central which certificate a client presented and which token it sent, and
  * central cannot check either itself (a certificate is public, and no key is shown). What central does narrow is what
  * auth, or whoever holds its token, can make of that: only a certificate issued through EST, of the current generation,
  * renews, and only in the last part of its life (`client-identity.renew-window`); one that has been replaced and is used
  * again closes the chain; and the chain ends at `max-age` whatever is asked.
  *
  * The certificate's subject is the one the client is registered by (`mtlsAuth`, or `CN=<client>,OU=<tenant>,O=Versola`
  * where it names none), never what a request asks for: a request that says anything else is refused, which is also
  * what RFC 7030 §4.2.2 asks of a re-enrolment (the subject and alternative names identical to the certificate being
  * renewed). A request supplies the public key and nothing more.
  *
  * The first certificate is traded for a one-time token an operator created ([[issueEnrollmentToken]], [[enroll]]);
  * every later one is asked for with the client's current certificate ([[renew]]), with a new key each time. The
  * certificates are short-lived, so what is revoked is the right to renew, not the certificate: it keeps authenticating
  * until it expires.
  *
  * That right is the real credential, so it is kept in generations. Every certificate belongs to the generation of the
  * enrolment it descends from. A re-enrolment ([[enroll]]), a revocation ([[revoke]]) and a detected fork each start a
  * new generation, and a certificate of an earlier one can no longer renew. A fork is detected the way a refresh-token
  * reuse is: a certificate that a renewal replaced and that comes to renew again, after a short grace for a client that
  * did not get the answer, means the key is in two hands.
  *
  * The certificates a client goes through are a chain, which begins at its enrolment. `client-identity.max-age` is how old
  * a chain may get; at that age central extends it itself (`auto-extend`) or, the default, stops renewing until an
  * operator extends it ([[extend]]): the client is re-approved on a schedule. The date is in every answer.
  */
trait ClientEstService:
  /** Registers a client that obtains its certificate through EST (`estEnrollment`), after checking it could be recognised by
    * what it will be issued. */
  def registerClient(
      request: CreateClientRequest,
  ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient]

  /** A token for `clientId` to enrol with, for one use and valid for `validity`. Returned here and never again: only
    * its hash is kept. */
  def issueEnrollmentToken(
      clientId: ClientId,
      validity: Duration = ClientEstService.TokenValidity,
  ): IO[ClientEstService.Refused | Throwable, ClientEstService.IssuedToken]

  /** The client's first certificate, for the token it was given. `claimedClientId` is the client the caller says it is
    * (the user name of HTTP Basic), if it says; it must be the token's own. Begins the chain. */
  def enroll(
      token: String,
      claimedClientId: Option[ClientId],
      csrPem: String,
  ): IO[ClientEstService.Refused | ClientEstService.InvalidCredentials.type | Throwable, ClientEstService.Issued]

  /** A new certificate for the client that proved who it is with `presentedCertificatePem`, on auth's mutual-TLS
    * listener. */
  def renew(
      presentedCertificatePem: String,
      csrPem: String,
  ): IO[ClientEstService.Refused | ClientEstService.InvalidCredentials.type | Throwable, ClientEstService.Issued]

  /** Starts the client's chain over, so that its next renewals are not refused for age. */
  def extend(clientId: ClientId): IO[ClientEstService.Refused | Throwable, Unit]

  /** Ends the right of every certificate issued so far to renew, and withdraws the tokens not yet used: an operator who
    * suspects the key of a client is out. The certificates keep authenticating until they expire. The client enrols
    * again with a new token. */
  def revoke(clientId: ClientId): IO[ClientEstService.Refused | Throwable, Unit]

  /** Where each EST client stands. */
  def states: Task[Map[ClientId, ClientEstService.State]]

object ClientEstService:
  val TokenValidity: Duration = 24.hours

  val MaxTokenValidity: Duration = 7.days

  /** How long after a renewal the certificate it replaced may still be used to renew, for a client that did not get the answer
    * and asks again. */
  val ForkGrace: Duration = 3.minutes

  /** `subject` is what the certificate must say, for the operator to give the client's side. */
  case class IssuedToken(token: String, expiresAt: Instant, subject: String)

  /** An issued certificate chain (leaf first, no key) and, if the chain of the client ends, when. */
  case class Issued(chain: String, chainEndsAt: Option[Instant])

  /** Where a client with EST enrolment stands: `pending` until its first certificate is issued, then when the latest one
    * expires and, where chains end, when the chain does. */
  case class State(
      pending: Boolean,
      certificateExpiresAt: Option[Instant],
      chainEndsAt: Option[Instant],
  ) derives Schema, JsonCodec

  /** Why central will not do what an authenticated caller asked. A refusal rather than a fault: retrying does not change it. */
  enum Refusal:
    /** The request is not what the registration entitles the client to: another subject, a weak or reused key. */
    case BadRequest

    /** The client may not have a certificate now: EST is not enabled for it, it is too early to renew, or its chain has
      * reached its limit. */
    case Forbidden

    /** A certificate that had been replaced was used to renew: the chain is in two hands, and has been closed. */
    case ChainClosed

  case class Refused(kind: Refusal, reason: String) extends RuntimeException(reason)

  /** The token or the presented certificate is unknown, spent, expired or for another client; from outside these are not
    * told apart. */
  case object InvalidCredentials extends RuntimeException("the credentials are not valid")

  private val requests = Metric.counter("est_certificate_requests_total")

  private def outcome(operation: String, result: String): UIO[Unit] =
    requests.tagged(Set(MetricLabel("operation", operation), MetricLabel("outcome", result))).increment

  val live: URLayer[
    ClientEstRepository & OAuthClientService & ClientCertificateIssuer & SecureRandom & CentralConfig,
    ClientEstService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      est: ClientEstRepository,
      clients: OAuthClientService,
      issuer: ClientCertificateIssuer,
      random: SecureRandom,
      config: CentralConfig,
  ) extends ClientEstService:
    private val validity: Duration =
      config.certificateBackends.map(_.validity)
        .orElse(config.clientCertificateAuthority.flatMap(_.validityDays).map(days => Duration.fromSeconds(days * 86400L)))
        .getOrElse(CentralConfig.ClientCertificatesConfig().validity)
    private val policy = config.clientIdentity
    private val maxAge: JavaDuration = JavaDuration.ofMillis(policy.maxAge.toMillis)
    private val renewWindow: Double = policy.renewWindow.max(0.0).min(1.0)

    private def endOf(started: Instant): Option[Instant] = Option.unless(policy.autoExtend)(started.plus(maxAge))

    override def registerClient(
        request: CreateClientRequest,
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient] =
      def invalid(reason: String) = InvalidRegistrationConfiguration(request.id, s"estEnrollment $reason")
      val auth = request.mtlsAuth.getOrElse(ClientCertificateRequests.defaultAuth(request.tenantId, request.id))
      for
        _ <- ZIO.fail(invalid(s"issues a tls_client_auth certificate, which ${request.authMethod} does not read"))
          .when(request.authMethod != AuthMethod.tls_client_auth)
        _ <- ZIO.fail(invalid("is for a client that holds its own key: a native app fronted by edge takes enrollEdgeClientCertificate"))
          .when(request.applicationType.contains(ApplicationType.native))
        _ <- ZIO.fail(invalid(
          "cannot be combined with issueEdgeClientCertificate or enrollEdgeClientCertificate - the key is generated by the client, not by central or an edge",
        ))
          .when(request.issueEdgeClientCertificate || request.enrollEdgeClientCertificate)
        _ <- ZIO.fail(invalid("cannot be combined with edgeClientCertificate - the certificate comes from EST, not from the registration"))
          .when(request.edgeClientCertificate.isDefined)
        _ <- ZIO.fromEither(ClientCertificateRequests.subjectFor(request.id, auth)).mapError(invalid(_))
        // Whatever names a certificate here names the client at `/token`: two clients registered by one subject could be
        // each other, so a subject is one client's.
        others <- clients.getAllClients
        _ <- ZIO.fromOption(others.find(other => other.id != request.id && other.mtlsAuth.contains(auth))).flip.mapError: other =>
          invalid(s"cannot be registered by $auth: client '${other.id}' is registered by it already")
        registered <- clients.registerClient(request.copy(mtlsAuth = Some(auth)))
        now <- Clock.instant
        // A client nothing will ever enrol is worse than a failed registration, so take it back rather than leave it.
        _ <- est.enable(request.id, now)
          .retry(Schedule.recurs(2) && Schedule.spaced(200.millis))
          .tapError(error =>
            Observability.setError("est_enrolment_not_recorded", Some(s"client '${request.id}' removed: $error")) *>
              clients.deleteClient(request.id).ignore,
          )
      yield registered

    override def issueEnrollmentToken(clientId: ClientId, validity: Duration): IO[Refused | Throwable, IssuedToken] =
      for
        _ <-
          ZIO.fail(Refused(Refusal.BadRequest, s"a token is valid for at most ${MaxTokenValidity.toHours} hours")).when(validity > MaxTokenValidity)
        _ <- ZIO.fail(Refused(Refusal.BadRequest, "a token must be valid for a positive time")).when(validity <= Duration.Zero)
        client <- estClient(clientId).orElseFail(Refused(Refusal.Forbidden, s"client '$clientId' is not registered with estEnrollment"))
        subject <- subjectOf(client)
        token <- random.nextBytes(32).map(Base64Url.encode)
        now <- Clock.instant
        expiresAt = now.plus(validity)
        _ <- est.createToken(hash(token), clientId, expiresAt)
      yield IssuedToken(token, expiresAt, subject.distinguishedName)

    override def enroll(
        token: String,
        claimedClientId: Option[ClientId],
        csrPem: String,
    ): IO[Refused | InvalidCredentials.type | Throwable, Issued] =
      counted("enroll"):
        for
          now <- Clock.instant
          // Nothing is parsed on the strength of a token before the token is known good: the caller is not yet authenticated,
          // so an unregistered client, a registered one and a bad token are one and the same answer.
          owner <- est.peekToken(hash(token), now)
          clientId <- ZIO.fromOption(owner.filter(id => claimedClientId.forall(_ == id))).orElseFail(InvalidCredentials)
          client <- estClient(clientId).orElseFail(InvalidCredentials)
          subject <- subjectOf(client)
          // Authenticated from here on, so a mistake in the request may be told -- and it is checked before the token is
          // spent, so it does not cost the operator a token.
          _ <- check(csrPem, subject, presentedKey = None)
          spent <- est.consumeToken(hash(token), now)
          // Another caller spent it between the look and now.
          _ <- ZIO.fail(InvalidCredentials).unless(spent.contains(clientId))
          // A token spent for an enrolment the CA could not serve is given back, so that the operator is not asked to make
          // another -- but only for that: once the CA has signed, or if the request is interrupted, the token stays spent,
          // and there is never a second certificate for one token.
          chain <- sign(subject, csrPem).tapError(_ => est.releaseToken(hash(token)).ignore)
          leaf <- verified(subject, chain)
          // The generation, the start of the chain and the certificate are recorded together.
          _ <- est.recordEnrollment(record(clientId, leaf, now), now)
          _ <- ZIO.logInfo(issuedMessage(clientId, leaf))
        yield Issued(chain, endOf(now))

    override def renew(presentedCertificatePem: String, csrPem: String): IO[Refused | InvalidCredentials.type | Throwable, Issued] =
      counted("renew"):
        for
          now <- Clock.instant
          presented <- ZIO.attempt(parse(presentedCertificatePem).head).orElseFail(InvalidCredentials)
          // Only a certificate central issued through EST renews: one an operator supplied, or issued for edge, is not
          // the start of a chain.
          known <- est.findCertificate(thumbprint(presented)).someOrFail(InvalidCredentials)
          // The handshake has checked the validity already; an expired certificate is one an operator must re-enrol.
          _ <- ZIO.fail(InvalidCredentials).when(now.isBefore(presented.getNotBefore.toInstant) || !now.isBefore(presented.getNotAfter.toInstant))
          // The right to renew belongs to the current generation: after a re-enrolment, a revocation or a fork the old
          // certificates are as good as unknown.
          current <- est.generation(known.clientId)
          _ <- ZIO.fail(InvalidCredentials).unless(current.contains(known.generation))
          client <- estClient(known.clientId).orElseFail(Refused(Refusal.Forbidden, "EST enrolment is not enabled for this client"))
          // A certificate a renewal replaced, used again after the grace: the key is in two hands. Close the chain, for the
          // client and for whoever has the copy: the operator enrols the client again.
          _ <- known.supersededAt match
            case Some(at) if now.isAfter(at.plus(ForkGrace)) =>
              est.revoke(client.id) *>
                Observability.setError("est_chain_fork_detected") *>
                ZIO.logWarning(s"the certificate chain of client '${client.id}' was closed: a certificate that had been replaced renewed again") *>
                ZIO.fail(Refused(
                  Refusal.ChainClosed,
                  "the certificate chain was closed: a certificate that had been replaced was used to renew. An operator enrols the client again",
                ))
            case _ => ZIO.unit
          subject <- subjectOf(client)
          _ <- ZIO.fail(Refused(Refusal.Forbidden, "the presented certificate does not carry the subject the client is registered by"))
            .unless(subject.describes(presented))
          // Only in the last part of its life: a certificate that is not near its end has no need of a successor, and a
          // caller that asks early is not the client's own routine.
          lifetime = JavaDuration.between(presented.getNotBefore.toInstant, presented.getNotAfter.toInstant)
          opens = presented.getNotAfter.toInstant.minusMillis((lifetime.toMillis * renewWindow).toLong)
          _ <- ZIO.fail(Refused(
            Refusal.Forbidden,
            s"it is too early to renew: from $opens, the last ${(renewWindow * 100).toInt}% of the certificate's life",
          ))
            .when(now.isBefore(opens))
          _ <- check(csrPem, subject, presentedKey = Some(presented))
          recorded <- est.chainStartedAt(client.id)
          // At the end of a chain: extend it, or stop, as the operator chose. Never a surprise: the client has been told the
          // date in every answer since its enrolment. Judged again, with the row locked, when the certificate is recorded.
          _ <- ZIO.fail(chainLimit(client.id)).when(!policy.autoExtend && recorded.exists(started => ended(started, now)))
          chain <- sign(subject, csrPem)
          leaf <- verified(subject, chain)
          outcome <- est.recordRenewal(thumbprint(presented), record(client.id, leaf, now), known.generation, now, policy.maxAge, policy.autoExtend)
          begun <- outcome match
            case RenewalRecorded.Recorded(start) => ZIO.succeed(start)
            // Revoked, or enrolled again, while the CA was answering: the certificate it signed is not recorded and cannot renew.
            case RenewalRecorded.GenerationChanged => ZIO.fail(InvalidCredentials)
            case RenewalRecorded.ChainEnded => ZIO.fail(chainLimit(client.id))
          _ <- ZIO.logInfo(issuedMessage(client.id, leaf))
        yield Issued(chain, endOf(begun))

    override def extend(clientId: ClientId): IO[Refused | Throwable, Unit] =
      for
        _ <- estClient(clientId).orElseFail(Refused(Refusal.Forbidden, s"client '$clientId' is not registered with estEnrollment"))
        now <- Clock.instant
        _ <- est.extendChain(clientId, now)
        _ <- ZIO.logInfo(s"the certificate chain of client '$clientId' was extended")
      yield ()

    override def revoke(clientId: ClientId): IO[Refused | Throwable, Unit] =
      for
        _ <- estClient(clientId).orElseFail(Refused(Refusal.Forbidden, s"client '$clientId' is not registered with estEnrollment"))
        _ <- est.revoke(clientId)
        _ <- ZIO.logInfo(s"the right of client '$clientId' to renew its certificate was revoked")
      yield ()

    override def states: Task[Map[ClientId, State]] =
      est.all.map:
        _.map: row =>
          row.clientId -> State(
            pending = row.latestNotAfter.isEmpty,
            certificateExpiresAt = row.latestNotAfter,
            chainEndsAt = row.chainStartedAt.flatMap(endOf),
          )
        .toMap

    /** The client, if it is registered to obtain its certificate through EST (and still can: `tls_client_auth`). */
    private def estClient(clientId: ClientId): IO[Unit | Throwable, OAuthClientRecord] =
      for
        enabled <- est.isEnabled(clientId)
        client <- clients.findClient(clientId).someOrFail(())
        _ <- ZIO.fail(()).unless(enabled && client.authMethod == AuthMethod.tls_client_auth)
      yield client

    private def subjectOf(client: OAuthClientRecord): IO[Refused, CertificateSubject] =
      val auth = client.mtlsAuth.getOrElse(ClientCertificateRequests.defaultAuth(client.tenantId, client.id))
      ZIO.fromEither(ClientCertificateRequests.subjectFor(client.id, auth)).mapError(Refused(Refusal.Forbidden, _))

    /** The request is signed by the key it names, says exactly the registered subject, asks for a key worth issuing for,
      * and (a renewal) not for the key of the certificate it replaces. */
    private def check(csrPem: String, subject: CertificateSubject, presentedKey: Option[X509Certificate]): IO[Refused, Unit] =
      for
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subject)).mapError: reason =>
          Refused(Refusal.BadRequest, s"$reason; the certificate subject is ${subject.distinguishedName}")
        key <- ZIO.fromEither(CertificateSubject.requestedKey(csrPem)).mapError(Refused(Refusal.BadRequest, _))
        _ <- ZIO.fail(Refused(Refusal.BadRequest, "a renewal needs a new key: the request reuses the key of the presented certificate"))
          .when(presentedKey.exists(cert => CertificateSubject.sameKey(cert.getPublicKey, key)))
      yield ()

    private def sign(subject: CertificateSubject, csrPem: String): Task[String] =
      issuer.sign(ClientCertificateRequests.signingRequest(csrPem, subject, validity))

    /** What the CA returned must say exactly what the client is registered by, or it would never be accepted at /token
      * (or worse, name an edge): refuse here rather than hand over a certificate that cannot be used. */
    private def verified(subject: CertificateSubject, chain: String): IO[Refused | Throwable, X509Certificate] =
      for
        leaf <- ZIO.attempt(parse(chain).head)
        _ <- ZIO.fail(Refused(Refusal.Forbidden, "the issued certificate does not carry exactly the registered subject"))
          .unless(subject.describes(leaf) && !EdgeCertificate.uriNames(leaf).exists(EdgeCertificate.isReserved))
      yield leaf

    private def record(clientId: ClientId, leaf: X509Certificate, now: Instant): NewEstCertificate =
      NewEstCertificate(
        clientId = clientId,
        thumbprint = thumbprint(leaf),
        serial = leaf.getSerialNumber.toString(16),
        notAfter = leaf.getNotAfter.toInstant,
        issuedAt = now,
      )

    private def issuedMessage(clientId: ClientId, leaf: X509Certificate): String =
      s"issued certificate ${leaf.getSerialNumber.toString(16)} to client '$clientId' through EST, valid until ${leaf.getNotAfter.toInstant}"

    private def ended(started: Instant, now: Instant): Boolean = JavaDuration.between(started, now).compareTo(maxAge) > 0

    private def chainLimit(clientId: ClientId): Refused =
      Refused(
        Refusal.Forbidden,
        s"the certificate chain of client '$clientId' reached its limit (${policy.maxAge.toDays} days since it was enrolled or last " +
          s"extended): an operator extends it with POST /configuration/clients/est/extend?clientId=$clientId",
      )

    private def counted[A](operation: String)(
        effect: IO[Refused | InvalidCredentials.type | Throwable, A],
    ): IO[Refused | InvalidCredentials.type | Throwable, A] =
      effect.tapBoth(
        {
          case InvalidCredentials => outcome(operation, "unauthorized")
          case Refused(Refusal.BadRequest, _) => outcome(operation, "bad_request")
          case Refused(Refusal.Forbidden, _) => outcome(operation, "forbidden")
          case Refused(Refusal.ChainClosed, _) => outcome(operation, "chain_fork")
          case _ => outcome(operation, "error")
        },
        _ => outcome(operation, "issued"),
      )

    private def parse(pem: String): List[X509Certificate] =
      CertificateFactory.getInstance("X.509")
        .generateCertificates(ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
        .toArray.toList.map(_.asInstanceOf[X509Certificate])

    private def thumbprint(certificate: X509Certificate): String = hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded))

    private def hash(token: String): String = hex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)))

    private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
