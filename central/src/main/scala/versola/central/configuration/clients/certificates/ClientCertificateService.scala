package versola.central.configuration.clients.certificates

import versola.central.CentralConfig
import versola.central.configuration.{CreateClientRequest, PatchClientRedirectUris, PatchClientScope, PatchPermissions, UpdateClientRequest}
import versola.central.configuration.clients.*
import versola.util.{Patch, PrivateClientCertificate}
import zio.*

import java.time.Instant

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

object ClientCertificateService:

  val live: URLayer[
    ClientCertificateIssuer & OAuthClientService & ClientCertificateIssuanceRepository & CentralConfig,
    ClientCertificateService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      issuer: ClientCertificateIssuer,
      clients: OAuthClientService,
      issuances: ClientCertificateIssuanceRepository,
      config: CentralConfig,
  ) extends ClientCertificateService:

    /** `client-certificates`, or -- for a deployment that only has the earlier
      * `client-certificate-authority` -- its own validity with the renewal defaults. */
    private val settings: CentralConfig.ClientCertificatesConfig =
      config.clientCertificates.getOrElse:
        val defaults = CentralConfig.ClientCertificatesConfig()
        config.clientCertificateAuthority.fold(defaults): authority =>
          defaults.copy(validity = Duration.fromSeconds(
            authority.validityDays.getOrElse(ClientCertificateAuthority.DefaultValidityDays) * 86400L,
          ))

    private case class Issued(certificate: PrivateClientCertificate, serial: String, notAfter: Instant)

    override def register(
        request: CreateClientRequest,
    ): IO[ClientAlreadyExists | InvalidRegistrationConfiguration | Throwable, RegisteredClient] =
      if !request.issueEdgeClientCertificate then clients.registerClient(request)
      else
        for
          // The checks a registration asking for a certificate has always been held to, except the one
          // refusing an `mtlsAuth`: given one, it is what the certificate is issued to be recognised by.
          _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateIssuedEdgeClientCertificate(
            request.id, true, request.authMethod, None, request.edgeClientCertificate,
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
          case _ => None
        )(ZIO.fail(_))
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
        settings = config.clientCertificates.orElse(
          config.clientCertificateAuthority.map(_ => CentralConfig.ClientCertificatesConfig()),
        )
        _ <- ZIO.foreachDiscard(settings): settings =>
          service.renewDue
            .catchAll(error => ZIO.logError(s"client certificate renewal pass failed: $error"))
            .repeat(Schedule.fixed(settings.checkInterval))
            .forkScoped
      yield ()
