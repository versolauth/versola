package versola.central.configuration.clients.est

import versola.central.CentralConfig
import versola.central.configuration.clients.certificates.{ClientCertificateIssuer, ClientCertificateRequests}
import versola.central.configuration.CreateClientRequest
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
  * through the CA behind [[ClientCertificateIssuer]], never with the client's key -- so a compromise of auth buys no
  * certificate.
  *
  * The certificate's subject is the one the client is registered by (`mtlsAuth`, or `CN=<client>,OU=<tenant>,O=Versola`
  * where it names none), never what a request asks for: a request that says anything else is refused, which is also
  * what RFC 7030 §4.2.2 asks of a re-enrolment (the subject and alternative names identical to the certificate being
  * renewed). A request supplies the public key and nothing more.
  *
  * The first certificate is traded for a one-time token an operator created ([[issueEnrollmentToken]], [[enroll]]);
  * every later one is asked for with the client's current certificate ([[renew]]), with a new key each time. The
  * certificates are short-lived, so revocation is expiry; the old one keeps working until it expires.
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

  /** Where each EST client stands. */
  def states: Task[Map[ClientId, ClientEstService.State]]

object ClientEstService:
  val TokenValidity: Duration = 24.hours

  val MaxTokenValidity: Duration = 7.days

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
    /** The client may not have a certificate now: EST is not enabled for it, or its chain has reached its limit. */
    case Forbidden

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
        _ <- ZIO.fail(invalid("cannot be combined with issueEdgeClientCertificate or enrollEdgeClientCertificate - the key is generated by the client, not by central or an edge"))
          .when(request.issueEdgeClientCertificate || request.enrollEdgeClientCertificate)
        _ <- ZIO.fail(invalid("cannot be combined with edgeClientCertificate - the certificate comes from EST, not from the registration"))
          .when(request.edgeClientCertificate.isDefined)
        _ <- ZIO.fromEither(ClientCertificateRequests.subjectFor(request.id, auth)).mapError(invalid(_))
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
        _ <- ZIO.fail(Refused(Refusal.BadRequest, s"a token is valid for at most ${MaxTokenValidity.toHours} hours")).when(validity > MaxTokenValidity)
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
          chain <- issue(client, subject, csrPem)
          _ <- est.startChain(clientId, now)
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
          client <- estClient(known.clientId).orElseFail(Refused(Refusal.Forbidden, "EST enrolment is not enabled for this client"))
          subject <- subjectOf(client)
          _ <- ZIO.fail(Refused(Refusal.Forbidden, "the presented certificate does not carry the subject the client is registered by"))
            .unless(subject.describes(presented))
          _ <- check(csrPem, subject, presentedKey = Some(presented))
          recorded <- est.chainStartedAt(client.id)
          started = recorded.getOrElse(known.issuedAt)
          aged = JavaDuration.between(started, now).compareTo(maxAge) > 0
          // At the end of a chain: extend it, or stop, as the operator chose. Never a surprise: the client has been told the
          // date in every answer since its enrolment.
          begun <-
            if !aged then ZIO.succeed(started)
            else if policy.autoExtend then est.startChain(client.id, now).as(now)
            else
              ZIO.fail(Refused(
                Refusal.Forbidden,
                s"the certificate chain of client '${client.id}' reached its limit (${policy.maxAge.toDays} days since it was enrolled or last " +
                  s"extended): an operator extends it with POST /configuration/clients/est/extend?clientId=${client.id}",
              ))
          chain <- issue(client, subject, csrPem)
        yield Issued(chain, endOf(begun))

    override def extend(clientId: ClientId): IO[Refused | Throwable, Unit] =
      for
        _ <- estClient(clientId).orElseFail(Refused(Refusal.Forbidden, s"client '$clientId' is not registered with estEnrollment"))
        now <- Clock.instant
        _ <- est.startChain(clientId, now)
        _ <- ZIO.logInfo(s"the certificate chain of client '$clientId' was extended")
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
          .when(presentedKey.exists(cert => MessageDigest.isEqual(cert.getPublicKey.getEncoded, key.getEncoded)))
      yield ()

    private def issue(client: OAuthClientRecord, subject: CertificateSubject, csrPem: String): IO[Refused | Throwable, String] =
      for
        chain <- issuer.sign(ClientCertificateRequests.signingRequest(csrPem, subject, validity))
        leaf <- ZIO.attempt(parse(chain).head)
        // What the CA returned must say exactly what the client is registered by, or it would never be accepted at /token
        // (or worse, name an edge): refuse here rather than hand over a certificate that cannot be used.
        _ <- ZIO.fail(Refused(Refusal.Forbidden, "the issued certificate does not carry exactly the registered subject"))
          .unless(subject.describes(leaf) && !EdgeCertificate.uriNames(leaf).exists(EdgeCertificate.isReserved))
        now <- Clock.instant
        _ <- est.recordCertificate(
          EstCertificate(
            clientId = client.id,
            thumbprint = thumbprint(leaf),
            serial = leaf.getSerialNumber.toString(16),
            notAfter = leaf.getNotAfter.toInstant,
            issuedAt = now,
          ),
        )
        _ <- ZIO.logInfo(s"issued certificate ${leaf.getSerialNumber.toString(16)} to client '${client.id}' through EST, valid until ${leaf.getNotAfter.toInstant}")
      yield chain

    private def counted[A](operation: String)(
        effect: IO[Refused | InvalidCredentials.type | Throwable, A],
    ): IO[Refused | InvalidCredentials.type | Throwable, A] =
      effect.tapBoth(
        {
          case InvalidCredentials => outcome(operation, "unauthorized")
          case Refused(Refusal.BadRequest, _) => outcome(operation, "bad_request")
          case Refused(Refusal.Forbidden, _) => outcome(operation, "forbidden")
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
