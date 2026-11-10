package versola.central.configuration.partners

import versola.central.CentralConfig
import versola.central.configuration.clients.{
  ApplicationType,
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
  EdgeGroupId,
  EdgeGroupKind,
  EdgeGroupRecord,
  EdgeGroupService,
  EdgeId,
  EdgeIdentityService,
  EdgeRecord,
  EdgeService,
}
import versola.central.configuration.metadata.ServerMetadataService
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
    * its first edge with its signing key, and one 24-hour enrollment token. The bundle is what the partner's operator pastes
    * into the edge. */
  def create(request: PartnerService.NewPartner): IO[PartnerError | Throwable, PartnerService.Created]

  def updateProfile(
      id: PartnerId,
      displayName: String,
      logoUri: Option[String],
      contact: PartnerContact,
      reviewAt: Option[Instant],
  ): IO[PartnerError | Throwable, Unit]

  /** The kill switch (#962 §5): the partner's clients leave the sync to auth and every edge's, their grants are revoked at
    * auth (which tells the partner edge over the back channel), and the partner's edges are refused everything from here on. */
  def suspend(id: PartnerId): IO[PartnerError | Throwable, Unit]

  def resume(id: PartnerId): IO[PartnerError | Throwable, Unit]

  /** New limits; the partner's clients now outside them are answered, not changed. */
  def setLimits(id: PartnerId, limits: PartnerLimits): IO[PartnerError | Throwable, Vector[PartnerService.ClientOutsideLimits]]

  def approvals(tenantId: Option[TenantId], status: Option[ApprovalStatus], partnerId: Option[PartnerId]): Task[Vector[PartnerApprovalRecord]]

  def approve(
      id: ApprovalId,
      decidedBy: Option[String],
      note: Option[String],
      overrides: Option[Json.Obj],
  ): IO[PartnerError | Throwable, PartnerApprovalRecord]

  def reject(id: ApprovalId, decidedBy: Option[String], note: String): IO[PartnerError | Throwable, PartnerApprovalRecord]

  /** `onboarding` becomes `active`: the partner's first edge has called central. Nothing for any other status. */
  def activate(partner: PartnerRecord): Task[Unit]

  def setSettings(partner: PartnerRecord, contact: PartnerContact, settings: PartnerSettings): Task[Unit]

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
  /** Enrollment tokens of partner edges live a day: a bundle travels to another organisation. */
  val TokenValidity: Duration = 24.hours

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

  /** An edge with the one-time material its bundle carries: the signing key and the enrollment token. */
  case class EnrolledEdge(edge: EdgeRecord, signingKey: RsaKeyPair, token: Token, bundle: String)

  case class Created(partner: PartnerRecord, edge: EnrolledEdge)

  case class ClientOutsideLimits(clientId: ClientId, fields: List[String])

  /** What the partner's operator pastes into a new edge: who it is, where central and auth are, its signing key and its
    * token. Pins of the listeners' certificates are P3. */
  case class Bundle(
      edgeId: EdgeId,
      group: EdgeGroupId,
      central: Bundle.Central,
      auth: Bundle.Auth,
      signingKey: Bundle.SigningKey,
      token: String,
  ) derives JsonCodec

  object Bundle:
    case class Central(enrollUrl: Option[String], mtlsUrl: Option[String]) derives JsonCodec
    case class Auth(issuer: Option[String], mtlsUrl: Option[String]) derives JsonCodec
    case class SigningKey(keyId: String, privateKey: String) derives JsonCodec

  val live: URLayer[
    PartnerRepository & PartnerRegistry & PartnerApprovalRepository & PartnerClientService & OAuthClientService & AuthorizationPresetService &
      EdgeGroupService & EdgeService & EdgeIdentityService & TenantRepository & AuthClient & ServerMetadataService & CentralConfig,
    PartnerService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _))

  /** What the operator may set as limits. */
  def validateLimits(limits: PartnerLimits): Option[PartnerError] =
    val methods = limits.clientAuthMethods
    if methods.isEmpty || !methods.subsetOf(PartnerLimits.AllowedAuthMethods) then
      Some(PartnerError.Invalid(s"limits.clientAuthMethods must be a non-empty subset of ${PartnerLimits.AllowedAuthMethods.mkString(", ")}"))
    else if limits.redirectDomains.isEmpty || limits.redirectDomains.exists(_.trim.isEmpty) then
      Some(PartnerError.Invalid("limits.redirectDomains must name at least one domain"))
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
      tenants: TenantRepository,
      auth: AuthClient,
      metadata: ServerMetadataService,
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
        )
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
        accessTokenTtl = partner.limits.accessTokenTtlMax.min(InvalidRegistrationConfiguration.MaxAccessTokenTtl.toSeconds).toInt,
        refreshTokenTtl = Some(partner.limits.refreshTokenTtlMax.min(Int.MaxValue.toLong).toInt),
        theme = "default",
        authFlow = None,
        otpTemplateId = "default",
        frontChannelLogoutUri = None,
        frontChannelLogoutSessionRequired = false,
        backChannelLogoutUri = None,
        logoUri = partner.logoUri,
        policyUri = None,
        tosUri = None,
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

    /** Registers an edge in the partner's group with its key, and issues its token and bundle. */
    private def enrol(partner: PartnerRecord, id: EdgeId, publicUrl: String): IO[PartnerError | Throwable, EnrolledEdge] =
      for
        keyPair <- edgeService.registerEdge(id, partner.groupId, Some(publicUrl))
        // the cache reads the row through its notification; the edge is read back from the row so that the answer is what was stored
        edge <- edgeService.findFresh(id).someOrFail(IllegalStateException(s"edge '$id' was registered and cannot be read"))
        token <- issueToken(id)
        bundle <- bundleFor(edge, keyPair, token)
      yield EnrolledEdge(edge, keyPair, token, bundle)

    private def issueToken(id: EdgeId): IO[PartnerError | Throwable, Token] =
      identity.issueEnrollmentToken(id, TokenValidity).mapBoth(
        {
          case refused: EdgeIdentityService.Refused => PartnerError.Invalid(refused.reason)
          case error: Throwable => error
        },
        issued => Token(issued.token, issued.expiresAt),
      )

    private def bundleFor(edge: EdgeRecord, keyPair: RsaKeyPair, token: Token): UIO[String] =
      metadata.getMetadata.map: document =>
        val issuer = document.flatMap(_.get("issuer")).collect { case Json.Str(value) => value }
        Bundle(
          edgeId = edge.id,
          group = edge.groupId,
          central = Bundle.Central(
            enrollUrl = config.publicUrls.map(_.enroll.encode),
            mtlsUrl = config.publicUrls.map(_.mtls.encode),
          ),
          auth = Bundle.Auth(issuer = issuer, mtlsUrl = config.auth.mtlsUrl.map(_.encode)),
          signingKey = Bundle.SigningKey(keyId = keyPair.keyId, privateKey = Base64Url.encode(keyPair.privateKey.getEncoded)),
          token = token.token,
        ).toJson

    override def updateProfile(
        id: PartnerId,
        displayName: String,
        logoUri: Option[String],
        contact: PartnerContact,
        reviewAt: Option[Instant],
    ): IO[PartnerError | Throwable, Unit] =
      partners.updateProfile(id, displayName, logoUri, contact, reviewAt).flatMap(found =>
        ZIO.fail(PartnerError.NotFound(s"partner '$id'")).unless(found),
      ).unit

    override def suspend(id: PartnerId): IO[PartnerError | Throwable, Unit] =
      for
        record <- partner(id)
        _ <- partners.setStatus(id, PartnerStatus.Suspended)
        // seen by the next sync of auth and by the next call of the partner's edges, not by the next refresh
        _ <- registry.refreshNow
        own <- clients.getPartnerClients(id)
        // Every grant of every client of the partner ends at auth, which tells the partner edge over the back channel; the
        // access tokens already issued run out within the partner's cap. The record is written first, so a client that
        // cannot be reached at auth is still omitted from the sync and refused at /token.
        _ <- ZIO.foreachDiscard(own.filter(_.isApproved))(client => auth.revokeRefreshTokensByClient(client.id))
      yield ()

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
    ): Task[Vector[PartnerApprovalRecord]] =
      approvals.list(tenantId, status, partnerId)

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
        applied <- approval.kind match
          case ApprovalKind.ProfileChange =>
            for
              record <- partner(approval.partnerId)
              change <- ZIO.fromEither(approval.requested.toJson.fromJson[ProfileChange])
                .mapError(reason => PartnerError.Invalid(s"the profile change cannot be read: $reason"))
              _ <- partners.updateProfile(record.id, change.displayName, change.logoUri, record.contact, record.reviewAt)
              _ <- registry.refreshNow
            yield None
          case _ => partnerClients.applyApproval(approval, overrides)
        now <- Clock.instant
        decided <- approvals.decide(id, ApprovalStatus.Approved, decidedBy, now, note, applied)
        _ <- ZIO.fail(PartnerError.Conflict(s"approval '$id' was decided meanwhile")).unless(decided)
      yield approval.copy(status = ApprovalStatus.Approved, decidedBy = decidedBy, decidedAt = Some(now), note = note, applied = applied)

    override def reject(id: ApprovalId, decidedBy: Option[String], note: String): IO[PartnerError | Throwable, PartnerApprovalRecord] =
      for
        approval <- waiting(id)
        _ <- partnerClients.rejectApproval(approval)
        now <- Clock.instant
        decided <- approvals.decide(id, ApprovalStatus.Rejected, decidedBy, now, Some(note), None)
        _ <- ZIO.fail(PartnerError.Conflict(s"approval '$id' was decided meanwhile")).unless(decided)
      yield approval.copy(status = ApprovalStatus.Rejected, decidedBy = decidedBy, decidedAt = Some(now), note = Some(note))

    override def activate(partner: PartnerRecord): Task[Unit] =
      ZIO.when(partner.status == PartnerStatus.Onboarding)(partners.setStatus(partner.id, PartnerStatus.Active) *> registry.refreshNow).unit

    override def setSettings(partner: PartnerRecord, contact: PartnerContact, settings: PartnerSettings): Task[Unit] =
      partners.setSettings(partner.id, contact, settings) *> registry.refreshNow

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
        token <- issueToken(edgeId)
      yield token

    override def edges(partner: PartnerRecord): UIO[Vector[EdgeRecord]] =
      edgeService.getAllEdges.map(_.filter(_.groupId == partner.groupId))

  /** The partner's own name and logo, as asked (`requested`) and as registered (`current`). */
  case class ProfileChange(displayName: String, logoUri: Option[String]) derives JsonCodec
