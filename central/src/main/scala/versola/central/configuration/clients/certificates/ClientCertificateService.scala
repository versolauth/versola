package versola.central.configuration.clients.certificates

import versola.central.CentralConfig
import versola.central.configuration.clients.*
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.{CreateClientRequest, PatchClientRedirectUris, PatchClientScope, PatchPermissions, UpdateClientRequest}
import versola.util.CertificateSubject
import versola.util.http.Observability
import versola.util.{Patch, PrivateClientCertificate}
import zio.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

/** Issues and renews the certificates edge-fronted clients present (#440).
  *
  * Central generates the key pair and the request; the CA behind [[ClientCertificateIssuer]]
  * signs it and central never holds its key. Certificates are short-lived, so a renewal is the
  * ordinary event and revocation is expiry.
  *
  * Only certificates central issued are renewed: each one has a row in
  * [[ClientCertificateIssuanceRepository]], and an operator who supplies their own
  * `edgeClientCertificate` through an update removes it (see [[forgetIfReplaced]]).
  */
trait ClientCertificateService:

  /** Registers a client, issuing its `edgeClientCertificate` first when the request asks for
    * `issueEdgeClientCertificate`. Otherwise exactly [[OAuthClientService.registerClient]]. */
  def register(
      request: CreateClientRequest,
  ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient]

  /** Replaces a managed certificate now, rather than at its renewal time. `false` when central
    * does not manage this client's certificate. */
  def renew(clientId: ClientId): IO[InvalidRegistrationConfiguration | Throwable, Boolean]

  /** Renews every managed certificate inside the renewal window. A failure on one client is
    * logged and does not stop the others; the count is how many were renewed. */
  def renewDue: Task[Int]

  /** An operator-supplied certificate replaces a managed one, and from then on is theirs. */
  def forgetIfReplaced(request: UpdateClientRequest): Task[Unit]

  /** Whether the client has its edge generate the certificate (#463): an update must not read the
    * certificate it does not store as missing. */
  def isEnrolled(clientId: ClientId): Task[Boolean]

  /** What the edge must put in the certificate it asks for, for each enrolled client among
    * `clients` (#463). */
  def enrollmentSubjects(clients: Vector[OAuthClientRecord]): Task[Map[ClientId, CertificateSubject]]

  /** Signs the request an edge made for a client it holds the key of, and returns the certificate
    * chain. Refused unless the client is enrolled and the request says exactly what the client is
    * registered by. Central never sees the key. */
  def sign(edgeId: EdgeId, clientId: ClientId, csrPem: String): IO[ClientCertificateService.SigningRefused | Throwable, String]

object ClientCertificateService:

  /** Why central will not sign an edge's request: the client is not enrolled, or the request is not
    * for what it is registered by. A refusal, not a fault -- the edge cannot fix it by retrying. */
  case class SigningRefused(clientId: ClientId, reason: String)
    extends RuntimeException(s"client '$clientId': $reason")

  val live: URLayer[
    ClientCertificateIssuer & OAuthClientService & ClientCertificateIssuanceRepository &
      EdgeCertificateEnrollmentRepository & CentralConfig,
    ClientCertificateService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      issuer: ClientCertificateIssuer,
      clients: OAuthClientService,
      issuances: ClientCertificateIssuanceRepository,
      enrollments: EdgeCertificateEnrollmentRepository,
      config: CentralConfig,
  ) extends ClientCertificateService:

    /** `client-certificates`, or -- for a deployment that only has the earlier
      * `client-certificate-authority` -- its own validity with the renewal defaults. */
    private val settings: CentralConfig.ClientCertificatesConfig =
      config.certificateBackends.getOrElse:
        val defaults = CentralConfig.ClientCertificatesConfig()
        config.clientCertificateAuthority.fold(defaults): authority =>
          defaults.copy(validity =
            Duration.fromSeconds(
              authority.validityDays.getOrElse(ClientCertificateAuthority.DefaultValidityDays) * 86400L,
            ),
          )

    private case class Issued(certificate: PrivateClientCertificate, serial: String, notAfter: Instant)

    override def register(
        request: CreateClientRequest,
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient] =
      if request.enrollEdgeClientCertificate then enrolling(request)
      else if !request.issueEdgeClientCertificate then clients.registerClient(request)
      else
        for
          // The checks a registration asking for a certificate has always been held to, except the one
          // refusing an `mtlsAuth`: given one, it is what the certificate is issued to be recognised by.
          _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateIssuedEdgeClientCertificate(
            request.id,
            true,
            request.authMethod,
            None,
            request.edgeClientCertificate,
          ))(ZIO.fail(_))
          auth = request.mtlsAuth.getOrElse(ClientCertificateRequests.defaultAuth(request.tenantId, request.id))
          issued <- issue(request.id, auth)
          registered <- clients.registerClient(request.copy(
            issueEdgeClientCertificate = false,
            mtlsAuth = Some(auth),
            edgeClientCertificate = Some(issued.certificate),
          ))
          now <- Clock.instant
          // The client is stored by now, so a record that cannot be written would leave a
          // certificate nothing renews: retry it, and failing that take the registration back, so
          // that retrying it is possible rather than answered `ClientAlreadyExists`.
          _ <- issuances.upsert(ClientCertificateIssuance(request.id, issued.serial, issued.notAfter, now))
            .retry(Schedule.recurs(2) && Schedule.spaced(200.millis))
            .tapError(error =>
              ZIO.logError(s"could not record the certificate issued to '${request.id}', removing the client: $error") *>
                clients.deleteClient(request.id).ignore,
            )
        yield registered

    /** The edge generates the key and asks for the certificate later; registration only records that
      * it will, after checking the client could be recognised by what it would ask for. */
    private def enrolling(
        request: CreateClientRequest,
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient] =
      def invalid(reason: String) = InvalidRegistrationConfiguration(request.id, s"enrollEdgeClientCertificate $reason")
      val auth = request.mtlsAuth.getOrElse(ClientCertificateRequests.defaultAuth(request.tenantId, request.id))
      for
        _ <- ZIO.fail(invalid(s"enrols a tls_client_auth certificate, which ${request.authMethod} does not read"))
          .when(request.authMethod != AuthMethod.tls_client_auth)
        // Tokens bound to the certificate (a web client's, RFC 8705 §3) must be presented with the very
        // certificate they were issued to, and replicas that each enrol for their own would present
        // different ones -- a session served by another replica, or after a renewal, would be refused.
        // A native app fronted by edge binds its tokens to the device's DPoP key instead, so any
        // replica's certificate does; a web client takes `issueEdgeClientCertificate`, one
        // certificate for every replica.
        _ <- ZIO.fail(invalid(
          "is for a native app fronted by edge (applicationType native): a web client's tokens are bound to its certificate, which replicas enrolling separately would not share - use issueEdgeClientCertificate",
        ))
          .unless(request.applicationType.contains(ApplicationType.native))
        _ <- ZIO.fail(invalid("cannot be combined with issueEdgeClientCertificate - the key is generated by the edge or by central, not both"))
          .when(request.issueEdgeClientCertificate)
        _ <- ZIO.fail(
          invalid("cannot be combined with edgeClientCertificate - edge presents the certificate it enrols for or the one it is given, not both"),
        )
          .when(request.edgeClientCertificate.isDefined)
        _ <- ZIO.fromEither(ClientCertificateRequests.subjectFor(request.id, auth)).mapError(invalid(_))
        registered <- clients.registerClient(request.copy(mtlsAuth = Some(auth)))
        now <- Clock.instant
        // Same reasoning as an issued certificate: a client nothing will ever enrol for is worse
        // than a failed registration, so take it back rather than leave it.
        _ <- enrollments.enroll(request.id, now)
          .retry(Schedule.recurs(2) && Schedule.spaced(200.millis))
          .tapError(error =>
            Observability.setError("edge_enrolment_not_recorded", Some(s"client '${request.id}' removed: $error")) *>
              clients.deleteClient(request.id).ignore,
          )
      yield registered

    override def isEnrolled(clientId: ClientId): Task[Boolean] = enrollments.isEnrolled(clientId)

    override def enrollmentSubjects(
        records: Vector[OAuthClientRecord],
    ): Task[Map[ClientId, CertificateSubject]] =
      enrollments.enrolledClients.map: enrolled =>
        records.filter(record => enrolled.contains(record.id)).flatMap: record =>
          record.mtlsAuth.flatMap(auth => ClientCertificateRequests.subjectFor(record.id, auth).toOption)
            .map(record.id -> _)
        .toMap

    override def sign(
        edgeId: EdgeId,
        clientId: ClientId,
        csrPem: String,
    ): IO[ClientCertificateService.SigningRefused | Throwable, String] =
      def refused(reason: String) = ClientCertificateService.SigningRefused(clientId, reason)
      for
        enrolled <- enrollments.isEnrolled(clientId)
        _ <- ZIO.fail(refused("is not enrolled to have an edge generate its certificate")).unless(enrolled)
        // The clients this edge is served, as the sync filters them: not every client central has. An
        // edge may ask only for one it is entitled to act for -- a tenant's clients are not another
        // tenant's edge's to obtain a certificate for.
        record <- clients.getClientsForSync(Some(edgeId)).map(_.find(_.id == clientId))
          .someOrFail(refused("is not one this edge serves"))
        auth <- ZIO.fromOption(record.mtlsAuth).orElseFail(refused("has no mtlsAuth to issue a certificate for"))
        subject <- ZIO.fromEither(ClientCertificateRequests.subjectFor(clientId, auth)).mapError(refused(_))
        _ <- ZIO.fromEither(CertificateSubject.matches(csrPem, subject)).mapError(refused(_))
        chain <- issuer.sign(ClientCertificateRequests.signingRequest(csrPem, subject, settings.validity))
        leaf <- ZIO.attempt:
          java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificates(java.io.ByteArrayInputStream(chain.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .iterator.next.asInstanceOf[java.security.cert.X509Certificate]
        // What the CA returned must say what the client is registered by, or auth would never
        // accept it: refuse here, with the reason, rather than hand the edge a certificate it
        // would present to a 401.
        _ <- ZIO.foreachDiscard(auth match
          case MutualTlsAuth.TlsClientAuth(subjectType, value) if !certificateValues(leaf, subjectType).contains(value) =>
            Some(RuntimeException(s"the issued certificate carries no $subjectType of '$value'"))
          case _ => None)(ZIO.fail(_))
        now <- Clock.instant
        _ <- enrollments.recordIssued(clientId, leaf.getSerialNumber.toString(16), now, edgeId)
          .catchAll(error => Observability.setError("edge_certificate_not_recorded", Some(s"client '$clientId': $error")))
        _ <- ZIO.logInfo(
          s"signed certificate ${leaf.getSerialNumber.toString(16)} for client '$clientId' at edge '$edgeId', valid until ${leaf.getNotAfter.toInstant}",
        )
      yield chain

    private def certificateValues(
        leaf: java.security.cert.X509Certificate,
        subjectType: MutualTlsSubjectType,
    ): Set[String] =
      def sans(tag: Int) = Option(leaf.getSubjectAlternativeNames).map(_.asScala.toList).getOrElse(Nil)
        .filter(_.get(0) == Integer.valueOf(tag)).map(_.get(1).toString).toSet
      import scala.jdk.CollectionConverters.*
      subjectType match
        case MutualTlsSubjectType.subject_dn => Set(leaf.getSubjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253))
        case MutualTlsSubjectType.san_email => sans(1)
        case MutualTlsSubjectType.san_dns => sans(2)
        case MutualTlsSubjectType.san_uri => sans(6)
        case MutualTlsSubjectType.san_ip => sans(7)

    override def renew(clientId: ClientId): IO[InvalidRegistrationConfiguration | Throwable, Boolean] =
      issuances.find(clientId).flatMap:
        case None => ZIO.succeed(false)
        case Some(_) => renewIssued(clientId).as(true)

    override def renewDue: Task[Int] =
      for
        now <- Clock.instant
        due <- issuances.expiringBefore(now.plus(settings.renewBefore))
        renewed <- ZIO.foreach(due): issuance =>
          renewIssued(issuance.clientId).as(1).catchAll: error =>
            ZIO.logError(s"renewing the certificate of client '${issuance.clientId}' failed: $error").as(0)
        _ <- ZIO.logInfo(s"renewed ${renewed.sum} client certificate(s)").when(renewed.sum > 0)
      yield renewed.sum

    override def forgetIfReplaced(request: UpdateClientRequest): Task[Unit] =
      issuances.delete(request.clientId).when(request.edgeClientCertificate.isDefined).unit

    private def renewIssued(clientId: ClientId): IO[InvalidRegistrationConfiguration | Throwable, Unit] =
      for
        record <- clients.getAllClients.map(_.find(_.id == clientId))
          .someOrFail(RuntimeException(s"client '$clientId' no longer exists"))
        auth <- ZIO.fromOption(record.mtlsAuth)
          .orElseFail(RuntimeException(s"client '$clientId' has no mtlsAuth to issue a certificate for"))
        issued <- issue(clientId, auth)
        // An operator may have replaced the certificate while the CA was answering: look again,
        // and never recreate the record afterwards (`replace` updates only an existing one).
        // What is left is the instant between this check and the update, which no store here
        // can close without a transaction across the two tables.
        stillManaged <- issuances.find(clientId).map(_.isDefined)
        _ <- if !stillManaged then
          ZIO.logInfo(s"client '$clientId' is no longer managed by central, discarding the certificate just issued")
        else
          for
            _ <- clients.updateClient(update(clientId, issued.certificate))
            now <- Clock.instant
            _ <- issuances.replace(ClientCertificateIssuance(clientId, issued.serial, issued.notAfter, now))
            _ <- ZIO.logInfo(s"issued certificate ${issued.serial} to client '$clientId', valid until ${issued.notAfter}")
          yield ()
      yield ()

    private def issue(clientId: ClientId, auth: MutualTlsAuth): IO[InvalidRegistrationConfiguration | Throwable, Issued] =
      for
        subject <- ZIO.fromEither(ClientCertificateRequests.subjectFor(clientId, auth))
          .mapError(reason => InvalidRegistrationConfiguration(clientId, reason))
        generated <- ClientCertificateRequests.generate(subject, settings.validity)
        chain <- issuer.sign(generated.request).mapError:
          case ClientCertificateIssuer.NotConfigured =>
            InvalidRegistrationConfiguration(
              clientId,
              "issueEdgeClientCertificate needs a CA, and central has none configured (client-certificates or client-certificate-authority)",
            )
          case ClientCertificateAuthority.Expired(at) =>
            InvalidRegistrationConfiguration.clientCertificateAuthorityExpired(clientId, at)
          case other => other
        certificate = PrivateClientCertificate(chain.trim + "\n" + generated.privateKeyPem)
        material <- ZIO.fromEither(certificate.material)
          .mapError(reason => RuntimeException(s"the CA returned a certificate central cannot use: $reason"))
        // The issued certificate must name what the registration compares it against; refuse
        // here, with a reason, rather than store one auth would never accept.
        _ <- ZIO.foreachDiscard(auth match
          case MutualTlsAuth.TlsClientAuth(subjectType, value)
              if !material.subjectValues(subjectType.toString).contains(value) =>
            Some(RuntimeException(s"the issued certificate carries no $subjectType of '$value'"))
          case _ => None)(ZIO.fail(_))
      yield Issued(certificate, material.leaf.getSerialNumber.toString(16), material.leaf.getNotAfter.toInstant)

    private def update(clientId: ClientId, certificate: PrivateClientCertificate): UpdateClientRequest =
      UpdateClientRequest(
        clientId = clientId,
        clientName = None,
        redirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
        scope = PatchClientScope(Set.empty, Set.empty),
        permissions = PatchPermissions(Set.empty, Set.empty),
        accessTokenTtl = None,
        refreshTokenTtl = None,
        theme = None,
        authFlow = None,
        registrationFlow = None,
        otpTemplateId = None,
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = None,
        backChannelLogoutUri = None,
        logoUri = None,
        policyUri = None,
        tosUri = None,
        consentFlow = None,
        dpopBoundAccessTokens = None,
        dpopSigningAlgs = None,
        dpopMinRsaKeySize = None,
        authMethod = None,
        mtlsAuth = None,
        certificateBoundAccessTokens = None,
        jwks = None,
        requireSignedRequestObject = None,
        requirePushedAuthorizationRequests = None,
        edgeSigningKey = None,
        edgeClientCertificate = Some(Patch.Modified(certificate)),
        applicationType = None,
      )

  /** Renews on a schedule for as long as the application runs; a no-op where no CA is configured. */
  val renewal: ZLayer[ClientCertificateService & CentralConfig, Nothing, Unit] =
    ZLayer.scoped:
      for
        config <- ZIO.service[CentralConfig]
        service <- ZIO.service[ClientCertificateService]
        settings = config.certificateBackends.orElse(
          config.clientCertificateAuthority.map(_ => CentralConfig.ClientCertificatesConfig()),
        )
        _ <- ZIO.foreachDiscard(settings): settings =>
          service.renewDue
            .catchAll(error => ZIO.logError(s"client certificate renewal pass failed: $error"))
            .repeat(Schedule.fixed(settings.checkInterval))
            .forkScoped
      yield ()
