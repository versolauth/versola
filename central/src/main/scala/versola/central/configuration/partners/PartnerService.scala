package versola.central.configuration.partners

import versola.central.CentralConfig
import versola.central.configuration.clients.{
  ApplicationType,
  AuthFlow,
  AuthMethod,
  AuthorizationPresetService,
  ClientAlreadyExists,
  ClientApproval,
  ClientId,
  ClientKind,
  ClientTemplate,
  InvalidRegistrationConfiguration,
  OAuthClientRecord,
  OAuthClientService,
  PartnerClientRules,
  PresetId,
  ResponseType,
}
import versola.central.configuration.edges.{
  EdgeEnrollmentTokenRepository,
  EdgeGroupId,
  EdgeGroupKind,
  EdgeGroupRecord,
  EdgeGroupService,
  EdgeId,
  EdgeIdentityService,
  EdgeRecord,
  EdgeService,
}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{AuthorizationPresetInput, CreateClientRequest, SaveAuthorizationPresetsRequest}
import versola.central.users.AuthClient
import versola.util.{Base64Url, RedirectUri, RsaKeyPair}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonCodec}
import zio.{Clock, Duration, IO, Random, Task, UIO, URLayer, ZIO, ZLayer, durationInt}

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Partners as the operator manages them (#549, #962): registered with their group, console client and first edge in one go,
  * limited, suspended and resumed, and their requests decided. The partner's own side -- activation, settings, a profile change,
  * another edge -- is here as well, since it writes the same record. */
trait PartnerService:

  /** Creates the partner (`onboarding`), its edge group (`id = partner id`, kind `partner`), its console client and preset,
    * its first edge (without a signing key: the edge makes its own and registers the public half when it enrols), and one
    * 24-hour enrollment token, which is all the partner's operator is given. */
  def create(request: PartnerService.NewPartner): IO[PartnerError | Throwable, PartnerService.Created]

  def updateProfile(
      id: PartnerId,
      displayName: String,
      logoUri: Option[String],
      contact: PartnerContact,
      reviewAt: Option[Instant],
  ): IO[PartnerError | Throwable, Unit]

  /** The kill switch (#962 §5), which disables the partner's edge and revokes nothing: the partner's clients leave the sync to
    * auth and every edge's, the partner's edges are refused everything from here on, and unused enrollment tokens are void.
    * Sessions, refresh tokens and access tokens already issued run out on their own. */
  def suspend(id: PartnerId): IO[PartnerError | Throwable, PartnerService.SuspendOutcome]

  def resume(id: PartnerId): IO[PartnerError | Throwable, Unit]

  /** New limits; the partner's clients now outside them are answered, not changed. */
  def setLimits(id: PartnerId, limits: PartnerLimits): IO[PartnerError | Throwable, Vector[PartnerService.ClientOutsideLimits]]

  def approvals(
      tenantId: Option[TenantId],
      status: Option[ApprovalStatus],
      partnerId: Option[PartnerId],
      limit: Int,
  ): Task[Vector[PartnerApprovalRecord]]

  def approve(
      id: ApprovalId,
      decidedBy: Option[String],
      note: Option[String],
      overrides: Option[Json.Obj],
  ): IO[PartnerError | Throwable, PartnerApprovalRecord]

  def reject(id: ApprovalId, decidedBy: Option[String], note: String): IO[PartnerError | Throwable, PartnerApprovalRecord]

  /** `onboarding` becomes `active`: the partner's first edge has called central. Nothing for any other status. */
  def activate(partner: PartnerRecord): Task[Unit]

  def setSettings(partner: PartnerRecord, contact: PartnerContact, settings: PartnerSettings): IO[PartnerError | Throwable, Unit]

  /** The partner asks to change its own name or logo: a `profile_change` request, applied by [[approve]]. */
  def requestProfileChange(
      partner: PartnerRecord,
      requestedBy: String,
      displayName: String,
      logoUri: Option[String],
  ): IO[PartnerError | Throwable, PartnerApprovalRecord]

  /** Another edge in the partner's group, within `maxEdges`, with its key and a token: one write, one answer (#977 review). */
  def addEdge(partner: PartnerRecord, id: EdgeId, publicUrl: String): IO[PartnerError | Throwable, PartnerService.EnrolledEdge]

  /** A fresh 24-hour token for an edge of the partner. */
  def enrollmentToken(partner: PartnerRecord, edgeId: EdgeId): IO[PartnerError | Throwable, PartnerService.Token]

  /** The edges of the partner's group. */
  def edges(partner: PartnerRecord): UIO[Vector[EdgeRecord]]

object PartnerService:
  /** Enrollment tokens of partner edges live a day: a token travels to another organisation. */
  val TokenValidity: Duration = 24.hours

  /** The longest access token of a console client, in seconds: the staff member's token authorises management calls (central checks
    * its signature, expiry, issuer and client, not a revocation), so it lives minutes. */
  val ConsoleAccessTokenTtlMax: Long = 600

  val ConsoleScope: Set[ScopeToken] = Set(ScopeToken("openid"), ScopeToken("phone"), ScopeToken("email"))

  case class NewPartner(
      id: PartnerId,
      tenantId: TenantId,
      displayName: String,
      logoUri: Option[String],
      contact: PartnerContact,
      limits: PartnerLimits,
      reviewAt: Option[Instant],
      consoleRedirectUri: RedirectUri,
      firstEdgeId: EdgeId,
      firstEdgePublicUrl: String,
  )

  case class Token(token: String, expiresAt: Instant)

  /** An edge with the one thing shown once: its enrollment token. The edge has no signing key yet, and central never holds a private
    * one for it. */
  case class EnrolledEdge(edge: EdgeRecord, token: Token)

  case class Created(partner: PartnerRecord, edge: EnrolledEdge)

  /** What a suspension did besides recording the status, which it always does: whether auth was told to reload its clients
    * (`authSynced`: until it has, auth still knows the partner's clients, and so a certificate of the group still authenticates
    * them there). */
  case class SuspendOutcome(authSynced: Boolean)

  case class ClientOutsideLimits(clientId: ClientId, fields: List[String])

  val live: URLayer[
    PartnerRepository & PartnerRegistry & PartnerApprovalRepository & PartnerClientService & OAuthClientService & AuthorizationPresetService &
      EdgeGroupService & EdgeService & EdgeIdentityService & EdgeEnrollmentTokenRepository & TenantRepository & AuthClient & CentralConfig,
    PartnerService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _))

  /** What the operator may set as limits. */
  def validateLimits(limits: PartnerLimits): Option[PartnerError] =
    val methods = limits.clientAuthMethods
    if methods.isEmpty || !methods.subsetOf(PartnerLimits.AllowedAuthMethods) then
      Some(PartnerError.Invalid(s"limits.clientAuthMethods must be a non-empty subset of ${PartnerLimits.AllowedAuthMethods.mkString(", ")}"))
    else if limits.redirectDomains.isEmpty || limits.redirectDomains.exists(_.trim.isEmpty) then
      Some(PartnerError.Invalid("limits.redirectDomains must name at least one domain"))
    else if limits.resourceDomains.exists(domains => domains.isEmpty || domains.exists(_.trim.isEmpty)) then
      Some(PartnerError.Invalid("limits.resourceDomains, when given, must name at least one domain"))
    else if limits.maxClients < 1 || limits.maxEdges < 1 then Some(PartnerError.Invalid("limits.maxClients and limits.maxEdges must be at least 1"))
    else if limits.accessTokenTtlMax < 1 || limits.refreshTokenTtlMax < 1 then
      Some(PartnerError.Invalid("limits.accessTokenTtlMax and limits.refreshTokenTtlMax must be positive"))
    else None

  class Impl(
      partners: PartnerRepository,
      registry: PartnerRegistry,
      approvals: PartnerApprovalRepository,
      partnerClients: PartnerClientService,
      clients: OAuthClientService,
      presets: AuthorizationPresetService,
      groups: EdgeGroupService,
      edgeService: EdgeService,
      identity: EdgeIdentityService,
      enrollmentTokens: EdgeEnrollmentTokenRepository,
      tenants: TenantRepository,
      auth: AuthClient,
      config: CentralConfig,
  ) extends PartnerService:

    private def nextId: UIO[ApprovalId] = Random.nextUUID.map(uuid => ApprovalId(uuid.toString))

    private def partner(id: PartnerId): IO[PartnerError | Throwable, PartnerRecord] =
      registry.findFresh(id).someOrFail(PartnerError.NotFound(s"partner '$id'"))

    private def registration[A](effect: IO[InvalidRegistrationConfiguration | ClientAlreadyExists | Throwable, A]): IO[PartnerError | Throwable, A] =
      effect.catchAll:
        case error: InvalidRegistrationConfiguration => ZIO.fail(PartnerError.Invalid(s"Invalid registration configuration: ${error.reason}"))
        case error: ClientAlreadyExists => ZIO.fail(PartnerError.Conflict(s"client '${error.clientId}' already exists"))
        case error: Throwable => ZIO.fail(error)

    override def create(request: NewPartner): IO[PartnerError | Throwable, Created] =
      val groupId = EdgeGroupId(request.id)
      val consoleId = PartnerRecord.consoleClientId(request.id)
      for
        _ <- requireEnrollmentAddress
        _ <- ZIO.foreachDiscard(validateLimits(request.limits))(ZIO.fail(_))
        _ <- tenants.getAll.map(
          _.exists(_.id == request.tenantId),
        ).filterOrFail(exists => exists)(PartnerError.NotFound(s"tenant '${request.tenantId}'"))
        // Everything that can be refused is refused before anything is written: the creation is several rows in several
        // tables, and a refusal halfway would leave a partner without its console or its edge.
        _ <-
          partners.find(request.id).flatMap(found => ZIO.fail(PartnerError.Conflict(s"partner '${request.id}' already exists")).when(found.isDefined))
        _ <- groups.find(groupId).flatMap(found => ZIO.fail(PartnerError.Conflict(s"edge group '$groupId' already exists")).when(found.isDefined))
        _ <-
          clients.findClient(consoleId).flatMap(found => ZIO.fail(PartnerError.Conflict(s"client '$consoleId' already exists")).when(found.isDefined))
        _ <- edgeService.findFresh(request.firstEdgeId).flatMap(found =>
          ZIO.fail(PartnerError.Conflict(s"edge '${request.firstEdgeId}' already exists")).when(found.isDefined),
        )
        _ <- ZIO.fail(PartnerError.Invalid("firstEdge.publicUrl must be an https URL")).unless(request.firstEdgePublicUrl.startsWith("https://"))
        _ <-
          ZIO.fail(PartnerError.Invalid("consoleClient.redirectUri must be an https URL")).unless(request.consoleRedirectUri.startsWith("https://"))
        now <- Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))
        record = PartnerRecord(
          id = request.id,
          tenantId = request.tenantId,
          displayName = request.displayName,
          logoUri = request.logoUri,
          contact = request.contact,
          status = PartnerStatus.Onboarding,
          // `openid` is what every partner client signs users in with: always in, whatever the operator typed
          limits = request.limits.copy(scopes = request.limits.scopes + ScopeToken("openid")),
          groupId = groupId,
          consoleClientId = consoleId,
          settings = PartnerSettings.empty,
          reviewAt = request.reviewAt,
          createdAt = now,
          version = 0L,
        )
        // The tenant's security profile admits the console client or it does not (a `fapi2` tenant holds it to its rules), and that is
        // known before anything is written: a refusal here leaves no group, no partner and no edge behind.
        _ <- registration(clients.checkSecurityProfile(consoleClient(record, request.consoleRedirectUri)))
        created <- groups.create(EdgeGroupRecord(groupId, EdgeGroupKind.Partner))
        _ <- ZIO.fail(PartnerError.Conflict(s"edge group '$groupId' already exists")).unless(created)
        _ <- partners.create(record).catchAll:
          case PartnerAlreadyExists(id) => ZIO.fail(PartnerError.Conflict(s"partner '$id' already exists"))
          case error: Throwable => ZIO.fail(error)
        _ <- registry.refreshNow
        // The console client: the partner's staff sign in to their console through it, on the group's certificate. Not held
        // to the partner's limits (its scope is what the console needs, its redirect is the console's), but a partner's
        // client in every other way.
        _ <- registration(clients.registerClient(consoleClient(record, request.consoleRedirectUri), allowOutsideLimits = true))
        // the preset is validated against the clients central has cached, which a notification refreshes after the commit
        _ <- clients.refreshNow
        console <- clients.findClient(consoleId).someOrFail(IllegalStateException(s"client '$consoleId' was registered and cannot be read"))
        _ <- consolePreset(console, request.consoleRedirectUri)
        edge <- enrol(record, request.firstEdgeId, request.firstEdgePublicUrl)
      yield Created(record, edge)

    private def consoleClient(partner: PartnerRecord, redirectUri: RedirectUri): CreateClientRequest =
      CreateClientRequest(
        tenantId = partner.tenantId,
        id = partner.consoleClientId,
        clientName = Map("en" -> s"${partner.displayName} console"),
        redirectUris = Set(redirectUri),
        allowedScopes = ConsoleScope,
        permissions = Set.empty,
        // short: the staff token the console is issued is what a management call is authorised by, and nothing revokes it
        accessTokenTtl = partner.limits.accessTokenTtlMax.min(
          PartnerService.ConsoleAccessTokenTtlMax,
        ).min(InvalidRegistrationConfiguration.MaxAccessTokenTtl.toSeconds).toInt,
        refreshTokenTtl = Some(partner.limits.refreshTokenTtlMax.min(Int.MaxValue.toLong).toInt),
        theme = "default",
        // the default of the platform until the operator says how its users sign in to the console (`PUT /configuration/clients/web`)
        authFlow = Some(AuthFlow.default),
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = partner.logoUri,
        policyUri = None,
        tosUri = None,
        // none: the console signs in the partner's own staff, not the operator's users; it is the one client of a partner without consent
        consentFlow = None,
        dpopBoundAccessTokens = false,
        dpopSigningAlgs = Set.empty,
        dpopMinRsaKeySize = None,
        authMethod = AuthMethod.tls_client_auth,
        mtlsAuth = Some(PartnerClientRules.groupAuth(partner)),
        certificateBoundAccessTokens = false,
        jwks = None,
        generateJwks = None,
        requireSignedRequestObject = false,
        requirePushedAuthorizationRequests = true,
        edgeSigningKey = None,
        edgeClientCertificate = None,
        template = Some(ClientTemplate(ClientKind.web)),
        applicationType = Some(ApplicationType.web),
        issueEdgeClientCertificate = false,
        enrollEdgeClientCertificate = false,
        themeOverrides = None,
        estEnrollment = false,
        partnerId = Some(partner.id),
      )

    /** `/login/<id>-console` and `/logout/<id>-console` are what the console uses. */
    private def consolePreset(console: OAuthClientRecord, redirectUri: RedirectUri): IO[PartnerError | Throwable, Unit] =
      presets.savePresets(SaveAuthorizationPresetsRequest(
        clientId = console.id,
        presets = List(AuthorizationPresetInput(
          id = PresetId(console.id),
          description = console.clientName.getOrElse("en", console.id),
          redirectUri = redirectUri,
          postLoginRedirectUri = redirectUri,
          postLogoutRedirectUri = None,
          scope = console.scope,
          responseType = ResponseType.Code,
          uiLocales = None,
          customParameters = Map.empty,
          cookieDomain = None,
          cookiePath = None,
        )),
      )).flatMap:
        case Right(()) => ZIO.unit
        case Left(error) => ZIO.fail(PartnerError.Conflict(s"the preset of the console client cannot be created: $error"))

    /** Registers an edge in the partner's group, without a signing key, and issues its token. */
    private def enrol(partner: PartnerRecord, id: EdgeId, publicUrl: String): IO[PartnerError | Throwable, EnrolledEdge] =
      for
        _ <- requireEnrollmentAddress
        // the count of edges is made by the repository that inserts the edge, under the partner's row lock, where two enrolments cannot
        // both pass
        _ <- edgeService.registerEdgeWithoutKey(id, partner.groupId, Some(publicUrl)).catchAll:
          case error: PartnerEdgeLimitReached =>
            ZIO.fail(PartnerError.Invalid(s"partner '${partner.id}' may run at most ${error.max} edges"))
          case error => ZIO.fail(error)
        // the token is issued for an edge the cache knows, which it learns through a notification after the commit: reloaded now
        _ <- edgeService.sync()
        // the edge is read back from the row so that the answer is what was stored
        edge <- edgeService.findFresh(id).someOrFail(IllegalStateException(s"edge '$id' was registered and cannot be read"))
        token <- issueToken(id)
      yield EnrolledEdge(edge, token)

    /** The token carries central's enrollment address, which is how an edge finds central: without one a token is of no use to a
      * partner, so no partner or edge is created. */
    private def requireEnrollmentAddress: IO[PartnerError, Unit] =
      ZIO.fail(PartnerError.Invalid("central.public-urls is not configured: an enrollment token would not say where to enrol"))
        .when(config.publicUrls.isEmpty).unit

    private def issueToken(id: EdgeId): IO[PartnerError | Throwable, Token] =
      identity.issueEnrollmentToken(id, TokenValidity).mapBoth(
        {
          case refused: EdgeIdentityService.Refused => PartnerError.Invalid(refused.reason)
          case error: Throwable => error
        },
        issued => Token(issued.token, issued.expiresAt),
      )

    override def updateProfile(
        id: PartnerId,
        displayName: String,
        logoUri: Option[String],
        contact: PartnerContact,
        reviewAt: Option[Instant],
    ): IO[PartnerError | Throwable, Unit] =
      for
        record <- partner(id)
        updated <- partners.updateProfile(id, record.version, displayName, logoUri, contact, reviewAt)
        _ <- ZIO.fail(PartnerError.Conflict(s"partner '$id' was changed meanwhile; read it again")).unless(updated)
        _ <- registry.refreshNow
      yield ()

    override def suspend(id: PartnerId): IO[PartnerError | Throwable, SuspendOutcome] =
      for
        record <- partner(id)
        _ <- partners.setStatus(id, PartnerStatus.Suspended)
        // seen by the next sync of auth and by the next call of the partner's edges, not by the next refresh
        _ <- registry.refreshNow
        // Auth is asked to reload its clients now, rather than at its next interval: it then omits the partner's, which is what makes a
        // certificate of the group useless there. Nothing is deleted or revoked: sessions, refresh tokens and access tokens that were
        // issued run out on their own.
        synced <- auth.syncConfiguration().as(true).catchAll: error =>
          ZIO.logWarning(s"partner '$id' is suspended and auth could not be told to reload its clients: $error").as(false)
        // an enrollment token that was printed and not used must not turn into a certificate later
        edgesOfPartner <- edges(record)
        _ <- ZIO.foreachDiscard(edgesOfPartner)(edge => enrollmentTokens.revoke(edge.id))
      yield SuspendOutcome(synced)

    override def resume(id: PartnerId): IO[PartnerError | Throwable, Unit] =
      for
        record <- partner(id)
        _ <- ZIO.fail(PartnerError.Conflict(s"partner '$id' is not suspended")).unless(record.status == PartnerStatus.Suspended)
        _ <- partners.setStatus(id, PartnerStatus.Active)
        _ <- registry.refreshNow
      yield ()

    override def setLimits(id: PartnerId, limits: PartnerLimits): IO[PartnerError | Throwable, Vector[ClientOutsideLimits]] =
      for
        record <- partner(id)
        _ <- ZIO.foreachDiscard(validateLimits(limits))(ZIO.fail(_))
        withOpenId = limits.copy(scopes = limits.scopes + ScopeToken("openid"))
        _ <- partners.setLimits(id, withOpenId)
        _ <- registry.refreshNow
        changed = record.copy(limits = withOpenId)
        own <- clients.getPartnerClients(id).map(_.filter(_.id != record.consoleClientId))
        outside = own.flatMap: client =>
          PartnerClientRules.outsideLimits(changed, PartnerClientRules.Subject.of(client)) match
            case Nil => None
            case violations => Some(ClientOutsideLimits(client.id, violations.map(_.field)))
      yield outside

    override def approvals(
        tenantId: Option[TenantId],
        status: Option[ApprovalStatus],
        partnerId: Option[PartnerId],
        limit: Int,
    ): Task[Vector[PartnerApprovalRecord]] =
      approvals.list(tenantId, status, partnerId, limit)

    private def waiting(id: ApprovalId): IO[PartnerError | Throwable, PartnerApprovalRecord] =
      approvals.find(id).someOrFail(PartnerError.NotFound(s"approval '$id'")).flatMap: approval =>
        ZIO.fail(PartnerError.Conflict(s"approval '$id' is ${approval.status.code}")).unless(approval.status == ApprovalStatus.Waiting).as(approval)

    override def approve(
        id: ApprovalId,
        decidedBy: Option[String],
        note: Option[String],
        overrides: Option[Json.Obj],
    ): IO[PartnerError | Throwable, PartnerApprovalRecord] =
      for
        approval <- waiting(id)
        // refused before anything is decided: a field that is not the operator's to override, or one that cannot be read
        applied <- partnerClients.checkOverrides(approval, overrides)
        now <- Clock.instant
        // The decision is the gate: `UPDATE ... WHERE status = 'waiting'`, so that of two decisions on one request (an approval
        // and a rejection, an approval and the partner withdrawing) exactly one is made, and only the one that was made goes on
        // to change the client. If applying it then fails, the decision is taken back and the client put back to pending, so
        // that a failure never leaves a client that is served while its request is undecided.
        won <- approvals.decide(id, ApprovalStatus.Approved, decidedBy, now, note, applied)
        _ <- ZIO.fail(PartnerError.Conflict(s"approval '$id' was decided meanwhile")).unless(won)
        _ <- applyDecision(approval, applied).tapError: error =>
          ZIO.logWarning(s"approval '$id' could not be applied and is waiting again: $error") *>
            partnerClients.revertApproval(approval).ignore *> approvals.reopen(id, ApprovalStatus.Approved).ignore
      yield approval.copy(status = ApprovalStatus.Approved, decidedBy = decidedBy, decidedAt = Some(now), note = note, applied = applied)

    private def applyDecision(approval: PartnerApprovalRecord, applied: Option[Json.Obj]): IO[PartnerError | Throwable, Unit] =
      approval.kind match
        case ApprovalKind.ProfileChange =>
          for
            record <- partner(approval.partnerId)
            change <- ZIO.fromEither(approval.requested.toJson.fromJson[ProfileChange])
              .mapError(reason => PartnerError.Invalid(s"the profile change cannot be read: $reason"))
            // the name and the logo alone: what either side has written to the contact since is not overwritten
            _ <- partners.setNameAndLogo(record.id, change.displayName, change.logoUri)
            _ <- registry.refreshNow
          yield ()
        case _ => partnerClients.applyApproval(approval, applied)

    override def reject(id: ApprovalId, decidedBy: Option[String], note: String): IO[PartnerError | Throwable, PartnerApprovalRecord] =
      for
        approval <- waiting(id)
        now <- Clock.instant
        // decided first, as an approval is: a request that was approved meanwhile stays approved
        won <- approvals.decide(id, ApprovalStatus.Rejected, decidedBy, now, Some(note), None)
        _ <- ZIO.fail(PartnerError.Conflict(s"approval '$id' was decided meanwhile")).unless(won)
        _ <- partnerClients.rejectApproval(approval)
      yield approval.copy(status = ApprovalStatus.Rejected, decidedBy = decidedBy, decidedAt = Some(now), note = Some(note))

    override def activate(partner: PartnerRecord): Task[Unit] =
      // conditional in the database: a read of `onboarding` that a suspension has since made stale must not undo it
      ZIO.when(partner.status == PartnerStatus.Onboarding)(partners.activate(partner.id).flatMap(activated =>
        registry.refreshNow.when(activated),
      )).unit

    override def setSettings(partner: PartnerRecord, contact: PartnerContact, settings: PartnerSettings): IO[PartnerError | Throwable, Unit] =
      for
        record <- this.partner(partner.id)
        updated <- partners.setSettings(partner.id, record.version, contact, settings)
        _ <- ZIO.fail(PartnerError.Conflict(s"partner '${partner.id}' was changed meanwhile; read the settings again")).unless(updated)
        _ <- registry.refreshNow
      yield ()

    override def requestProfileChange(
        partner: PartnerRecord,
        requestedBy: String,
        displayName: String,
        logoUri: Option[String],
    ): IO[PartnerError | Throwable, PartnerApprovalRecord] =
      for
        _ <- ZIO.fail(PartnerError.Invalid("displayName must not be empty")).when(displayName.trim.isEmpty)
        open <- approvals.waitingFor(partner.id, partner.id)
        _ <- ZIO.fail(PartnerError.Conflict(s"partner '${partner.id}' already has a profile change waiting for the operator")).when(open.nonEmpty)
        now <- Clock.instant
        id <- nextId
        approval = PartnerApprovalRecord(
          id = id,
          partnerId = partner.id,
          kind = ApprovalKind.ProfileChange,
          targetId = partner.id,
          requested = ProfileChange(displayName, logoUri).toJsonAST.toOption.collect { case obj: Json.Obj => obj }.getOrElse(Json.Obj()),
          current =
            Some(ProfileChange(partner.displayName, partner.logoUri).toJsonAST.toOption.collect { case obj: Json.Obj => obj }.getOrElse(Json.Obj())),
          applied = None,
          outsideLimits = List("displayName", "logoUri"),
          requestedBy = requestedBy,
          requestedAt = now,
          status = ApprovalStatus.Waiting,
          decidedBy = None,
          decidedAt = None,
          note = None,
        )
        _ <- approvals.create(approval)
      yield approval

    override def addEdge(partner: PartnerRecord, id: EdgeId, publicUrl: String): IO[PartnerError | Throwable, EnrolledEdge] =
      for
        own <- edges(partner)
        _ <- ZIO.fail(
          PartnerError.Invalid(s"partner '${partner.id}' may run at most ${partner.limits.maxEdges} edges"),
        ).when(own.size >= partner.limits.maxEdges)
        _ <- ZIO.fail(PartnerError.Invalid("publicUrl must be an https URL")).unless(publicUrl.startsWith("https://"))
        _ <- edgeService.findFresh(id).flatMap(found => ZIO.fail(PartnerError.Conflict(s"edge '$id' already exists")).when(found.isDefined))
        enrolled <- enrol(partner, id, publicUrl)
      yield enrolled

    override def enrollmentToken(partner: PartnerRecord, edgeId: EdgeId): IO[PartnerError | Throwable, Token] =
      for
        own <- edges(partner)
        _ <- ZIO.fail(PartnerError.NotFound(s"edge '$edgeId'")).unless(own.exists(_.id == edgeId))
        _ <- requireEnrollmentAddress
        token <- issueToken(edgeId)
      yield token

    override def edges(partner: PartnerRecord): UIO[Vector[EdgeRecord]] =
      edgeService.getAllEdges.map(_.filter(_.groupId == partner.groupId))

  /** The partner's own name and logo, as asked (`requested`) and as registered (`current`). */
  case class ProfileChange(displayName: String, logoUri: Option[String]) derives JsonCodec
