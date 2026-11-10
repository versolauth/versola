package versola.central.configuration.partners

import versola.central.configuration.clients.{
  AuthorizationPresetService,
  ClientAlreadyExists,
  ClientApproval,
  ClientId,
  InvalidRegistrationConfiguration,
  OAuthClientRecord,
  OAuthClientService,
  PartnerClientRules,
  PresetId,
  ResponseType,
}
import versola.central.configuration.{AuthorizationPresetInput, SaveAuthorizationPresetsRequest}
import versola.util.{JsonWebKeySet, Patch}
import zio.json.ast.Json
import zio.json.{DecoderOps, EncoderOps, JsonDecoder, JsonEncoder}
import zio.{Clock, Duration, IO, Random, Task, UIO, URLayer, ZIO, ZLayer}

import java.time.Instant

/** The clients a partner owns, as its edge manages them on its behalf (#549 decision 7): a new client always waits for the
  * operator; a change inside the limits applies at once; one that touches the consent screen or goes outside the limits
  * waits too. The operator's decisions ([[applyApproval]], [[rejectApproval]]) are made here as well, so that what a partner
  * asked for and what the operator granted are read the same way. */
trait PartnerClientService:

  /** Registers the client `pending`, outside the limits or not, and records the `client_create` request: `outsideLimits`
    * names the invariant's violations and, always, `clientName` and `logoUri`. What makes the client no partner's client
    * at all (`PartnerClientRules.invariant`), and the limit on the number of clients, are refused as [[PartnerError.Invalid]]. */
  def create(
      partner: PartnerRecord,
      request: PartnerClientRequest,
      requestedBy: String,
  ): IO[PartnerError | Throwable, (OAuthClientRecord, PartnerApprovalRecord)]

  /** Applies the change when it stays inside the limits and leaves the consent screen alone (`Right`), else records a
    * `client_change` request and changes nothing (`Left`). */
  def update(
      partner: PartnerRecord,
      request: PartnerClientUpdate,
      requestedBy: String,
  ): IO[PartnerError | Throwable, Either[PartnerApprovalRecord, Unit]]

  /** Withdraws the waiting request about the client: a `client_create` withdrawn deletes the client, a `client_change`
    * withdrawn leaves it as registered. */
  def withdraw(partner: PartnerRecord, clientId: ClientId): IO[PartnerError | Throwable, PartnerApprovalRecord]

  def delete(partner: PartnerRecord, clientId: ClientId): IO[PartnerError | Throwable, Unit]

  /** New `private_key_jwt` keys for the client; a change that needs no approval. */
  def replaceKeys(partner: PartnerRecord, clientId: ClientId, jwks: JsonWebKeySet): IO[PartnerError | Throwable, Unit]

  /** The partner's clients with where each stands, the console client excepted. */
  def list(partner: PartnerRecord): Task[Vector[PartnerClientService.View]]

  def find(partner: PartnerRecord, clientId: ClientId): Task[Option[PartnerClientService.View]]

  /** The operator approves a client request: `overrides` laid over `requested` is what gets registered, and what comes
    * back as `applied` when it differs from `requested`. A `client_create` sets the client `approved` and gives it its
    * preset (`/login/<client id>`). */
  def applyApproval(approval: PartnerApprovalRecord, overrides: Option[Json.Obj]): IO[PartnerError | Throwable, Option[Json.Obj]]

  /** The operator rejects: a `client_create` sets the client `rejected`; a `client_change` leaves it as registered. */
  def rejectApproval(approval: PartnerApprovalRecord): IO[PartnerError | Throwable, Unit]

object PartnerClientService:

  /** Where a client stands, in the terms the partner's console shows (#963 `PartnerClient.approval`). */
  enum RequestKind:
    case create, change

  enum RequestState:
    case pending, approved, rejected

  case class Approval(kind: RequestKind, status: RequestState, waitingFields: List[String], requestedAt: Option[Instant], note: Option[String])

  case class View(client: OAuthClientRecord, approval: Approval)

  /** The fields the consent screen shows: a change of them always needs the operator (consent phishing). */
  val ConsentScreenFields: List[String] = List("clientName", "logoUri")

  val live: URLayer[OAuthClientService & AuthorizationPresetService & PartnerApprovalRepository, PartnerClientService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      clients: OAuthClientService,
      presets: AuthorizationPresetService,
      approvals: PartnerApprovalRepository,
  ) extends PartnerClientService:

    /** An id nobody chooses: random, through ZIO's `Random`, so that a test can make it predictable. */
    private def nextId: UIO[ApprovalId] = Random.nextUUID.map(uuid => ApprovalId(uuid.toString))

    private def invalid[A](error: InvalidRegistrationConfiguration | ClientAlreadyExists | Throwable): IO[PartnerError | Throwable, A] =
      error match
        case error: InvalidRegistrationConfiguration => ZIO.fail(PartnerError.Invalid(s"Invalid registration configuration: ${error.reason}"))
        case error: ClientAlreadyExists => ZIO.fail(PartnerError.Conflict(s"client '${error.clientId}' already exists"))
        case error: Throwable => ZIO.fail(error)

    private def owned(partner: PartnerRecord, clientId: ClientId): IO[PartnerError | Throwable, OAuthClientRecord] =
      clients.findClient(clientId).flatMap:
        case Some(client) if client.partnerId.contains(partner.id) && client.id != partner.consoleClientId => ZIO.succeed(client)
        case _ => ZIO.fail(PartnerError.NotFound(s"client '$clientId'"))

    private def toJson[A: JsonEncoder](value: A): Json.Obj =
      value.toJsonAST.toOption.collect { case obj: Json.Obj => obj }.getOrElse(Json.Obj())

    override def create(
        partner: PartnerRecord,
        request: PartnerClientRequest,
        requestedBy: String,
    ): IO[PartnerError | Throwable, (OAuthClientRecord, PartnerApprovalRecord)] =
      for
        _ <- ZIO.fail(PartnerError.Conflict(s"client '${request.id}' is the partner's console client")).when(request.id == partner.consoleClientId)
        registration = request.toCreateClientRequest(partner)
        _ <- clients.registerClient(registration, approval = ClientApproval.Pending, allowOutsideLimits = true).catchAll(invalid)
        client <- clients.findClient(request.id).someOrFail(IllegalStateException(s"client '${request.id}' was registered and cannot be read"))
        outside = PartnerClientRules.outsideLimits(partner, PartnerClientRules.Subject.of(client)).map(_.field)
        now <- Clock.instant
        id <- nextId
        approval = PartnerApprovalRecord(
          id = id,
          partnerId = partner.id,
          kind = ApprovalKind.ClientCreate,
          targetId = request.id,
          requested = toJson(request),
          current = None,
          applied = None,
          outsideLimits = (outside ++ ConsentScreenFields).distinct,
          requestedBy = requestedBy,
          requestedAt = now,
          status = ApprovalStatus.Waiting,
          decidedBy = None,
          decidedAt = None,
          note = None,
        )
        _ <- approvals.create(approval)
      yield (client, approval)

    override def update(
        partner: PartnerRecord,
        request: PartnerClientUpdate,
        requestedBy: String,
    ): IO[PartnerError | Throwable, Either[PartnerApprovalRecord, Unit]] =
      for
        client <- owned(partner, request.id)
        _ <- ZIO.fail(PartnerError.Conflict(s"client '${request.id}' is waiting for the operator")).when(client.approval == ClientApproval.Pending)
        waiting <- approvals.waitingFor(partner.id, request.id)
        _ <- ZIO.fail(PartnerError.Conflict(s"client '${request.id}' already has a change waiting for the operator")).when(waiting.nonEmpty)
        patched = patchedSubject(request, client)
        outside = PartnerClientRules.outsideLimits(partner, patched).map(_.field)
        needsOperator = outside ++ ConsentScreenFields.filter(request.fields.contains)
        result <-
          if needsOperator.isEmpty then
            clients.updateClient(request.toUpdateClientRequest(client)).catchAll(invalid).as(Right(()))
          else
            for
              now <- Clock.instant
              id <- nextId
              approval = PartnerApprovalRecord(
                id = id,
                partnerId = partner.id,
                kind = ApprovalKind.ClientChange,
                targetId = request.id,
                requested = toJson(request),
                current = Some(currentFields(client, request.fields)),
                applied = None,
                outsideLimits = needsOperator.distinct,
                requestedBy = requestedBy,
                requestedAt = now,
                status = ApprovalStatus.Waiting,
                decidedBy = None,
                decidedAt = None,
                note = None,
              )
              _ <- approvals.create(approval)
            yield Left(approval)
      yield result

    /** The client as the update would leave it, in what the limits read. */
    private def patchedSubject(request: PartnerClientUpdate, client: OAuthClientRecord): PartnerClientRules.Subject =
      val subject = PartnerClientRules.Subject.of(client)
      subject.copy(
        redirectUris = request.redirectUris.getOrElse(subject.redirectUris),
        scope = request.scope.getOrElse(subject.scope),
        accessTokenTtl = request.accessTokenTtl.map(Duration.fromSeconds).getOrElse(subject.accessTokenTtl),
        refreshTokenTtl = request.refreshTokenTtl.map(Duration.fromSeconds).getOrElse(subject.refreshTokenTtl),
      )

    /** The stored values of the fields a change names, for the operator to see the difference. */
    private def currentFields(client: OAuthClientRecord, fields: Set[String]): Json.Obj =
      val all = Json.Obj(
        "clientName" -> toJson(client.clientName),
        "logoUri" -> client.logoUri.fold(Json.Null)(Json.Str(_)),
        "policyUri" -> client.policyUri.fold(Json.Null)(Json.Str(_)),
        "tosUri" -> client.tosUri.fold(Json.Null)(Json.Str(_)),
        "redirectUris" -> Json.Arr(client.redirectUris.toList.sortBy(uri => uri: String).map(uri => Json.Str(uri))*),
        "scope" -> Json.Arr(client.scope.toList.sorted.map(Json.Str(_))*),
        "accessTokenTtl" -> Json.Num(client.accessTokenTtl.toSeconds),
        "refreshTokenTtl" -> Json.Num(client.refreshTokenTtl.toSeconds),
        "backChannelLogoutUri" -> client.backChannelLogoutUri.fold(Json.Null)(url => Json.Str(url.encode)),
      )
      Json.Obj(all.fields.filter((name, _) => fields.contains(name)))

    override def withdraw(partner: PartnerRecord, clientId: ClientId): IO[PartnerError | Throwable, PartnerApprovalRecord] =
      for
        client <- owned(partner, clientId)
        waiting <- approvals.waitingFor(
          partner.id,
          clientId,
        ).map(_.headOption).someOrFail(PartnerError.NotFound(s"a waiting request about client '$clientId'"))
        now <- Clock.instant
        _ <- approvals.decide(waiting.id, ApprovalStatus.Withdrawn, None, now, None, None)
        _ <- clients.deleteClient(clientId).when(waiting.kind == ApprovalKind.ClientCreate)
      yield waiting.copy(status = ApprovalStatus.Withdrawn, decidedAt = Some(now))

    override def delete(partner: PartnerRecord, clientId: ClientId): IO[PartnerError | Throwable, Unit] =
      for
        _ <- owned(partner, clientId)
        waiting <- approvals.waitingFor(partner.id, clientId)
        now <- Clock.instant
        _ <- ZIO.foreachDiscard(waiting)(request => approvals.decide(request.id, ApprovalStatus.Withdrawn, None, now, None, None))
        _ <- clients.deleteClient(clientId)
      yield ()

    override def replaceKeys(partner: PartnerRecord, clientId: ClientId, jwks: JsonWebKeySet): IO[PartnerError | Throwable, Unit] =
      for
        client <- owned(partner, clientId)
        _ <- clients.updateClient(PartnerClientUpdate.of(clientId).copy(jwks = Some(jwks)).toUpdateClientRequest(client)).catchAll(invalid)
      yield ()

    override def list(partner: PartnerRecord): Task[Vector[View]] =
      for
        own <- clients.getPartnerClients(partner.id).map(_.filter(_.id != partner.consoleClientId).sortBy(_.id))
        requests <- approvals.list(None, None, Some(partner.id))
      yield own.map(client => View(client, stateOf(client, requests.filter(_.targetId == client.id))))

    override def find(partner: PartnerRecord, clientId: ClientId): Task[Option[View]] =
      list(partner).map(_.find(_.client.id == clientId))

    /** Where a client stands, from its approval and the requests about it (newest first). */
    private def stateOf(client: OAuthClientRecord, requests: Vector[PartnerApprovalRecord]): Approval =
      val creation = requests.find(_.kind == ApprovalKind.ClientCreate)
      client.approval match
        case ClientApproval.Pending =>
          Approval(RequestKind.create, RequestState.pending, creation.fold(ConsentScreenFields)(_.outsideLimits), creation.map(_.requestedAt), None)
        case ClientApproval.Rejected =>
          Approval(RequestKind.create, RequestState.rejected, Nil, creation.map(_.requestedAt), creation.flatMap(_.note))
        case ClientApproval.Approved =>
          requests.find(r => r.kind == ApprovalKind.ClientChange && r.status == ApprovalStatus.Waiting) match
            case Some(change) => Approval(RequestKind.change, RequestState.pending, change.outsideLimits, Some(change.requestedAt), None)
            case None => Approval(RequestKind.create, RequestState.approved, Nil, creation.map(_.requestedAt), creation.flatMap(_.note))

    override def applyApproval(approval: PartnerApprovalRecord, overrides: Option[Json.Obj]): IO[PartnerError | Throwable, Option[Json.Obj]] =
      val clientId = ClientId(approval.targetId)
      val applied = overrides.filter(_.fields.nonEmpty).map(merge(approval.requested, _))
      def decode[A: JsonDecoder](json: Json.Obj, what: String): IO[PartnerError, A] =
        ZIO.fromEither(json.toJson.fromJson[A]).mapError(reason => PartnerError.Invalid(s"the $what cannot be read: $reason"))
      for
        client <- clients.findClient(clientId).someOrFail(PartnerError.NotFound(s"client '$clientId'"))
        _ <- approval.kind match
          case ApprovalKind.ClientCreate =>
            for
              // the overrides are a change to what the partner registered: read as one, so the same rules apply
              _ <- ZIO.foreachDiscard(applied): json =>
                decode[PartnerClientRequest](json, "approved registration").flatMap: request =>
                  clients.updateClient(asUpdate(request).toUpdateClientRequest(client), allowOutsideLimits = true).catchAll(invalid)
              _ <- clients.setApproval(clientId, ClientApproval.Approved)
              approved <- clients.findClient(clientId).someOrFail(PartnerError.NotFound(s"client '$clientId'"))
              _ <- loginPreset(approved)
            yield ()
          case ApprovalKind.ClientChange =>
            decode[PartnerClientUpdate](applied.getOrElse(approval.requested), "approved change").flatMap: request =>
              clients.updateClient(request.copy(id = clientId).toUpdateClientRequest(client), allowOutsideLimits = true).catchAll(invalid)
          case ApprovalKind.ProfileChange =>
            ZIO.fail(PartnerError.Invalid("a profile change is not a client request"))
      yield applied

    override def rejectApproval(approval: PartnerApprovalRecord): IO[PartnerError | Throwable, Unit] =
      approval.kind match
        case ApprovalKind.ClientCreate =>
          clients.setApproval(ClientId(approval.targetId), ClientApproval.Rejected).unit
        case _ => ZIO.unit

    /** `overrides` laid over `requested`: a field named in both is the operator's. */
    private def merge(requested: Json.Obj, overrides: Json.Obj): Json.Obj =
      val overridden = overrides.fields.map(_._1).toSet
      Json.Obj(requested.fields.filterNot((name, _) => overridden.contains(name)) ++ overrides.fields)

    /** A registration read as the change it is to the client registered from it. */
    private def asUpdate(request: PartnerClientRequest): PartnerClientUpdate =
      PartnerClientUpdate(
        id = request.id,
        clientName = Some(request.clientName),
        logoUri = Some(request.logoUri.fold(Patch.Deleted)(Patch.Modified(_))),
        policyUri = Some(request.policyUri.fold(Patch.Deleted)(Patch.Modified(_))),
        tosUri = Some(request.tosUri.fold(Patch.Deleted)(Patch.Modified(_))),
        redirectUris = Some(request.redirectUris),
        scope = Some(request.scope),
        jwks = request.jwks,
        accessTokenTtl = Some(request.accessTokenTtl.toLong),
        refreshTokenTtl = request.refreshTokenTtl.map(_.toLong),
        backChannelLogoutUri = Some(request.backChannelLogoutUri.fold(Patch.Deleted)(Patch.Modified(_))),
        consentFlow = request.consentFlow,
      )

    /** The one preset of an approved partner client, `id = client id`: the partner's "Sign in with" button is
      * `https://<edge>/login/<client id>` (#963 `PartnerClient.loginPath`). Its redirect is the client's first one. */
    private def loginPreset(client: OAuthClientRecord): IO[PartnerError | Throwable, Unit] =
      ZIO.foreachDiscard(client.redirectUris.toList.sortBy(uri => uri: String).headOption): redirect =>
        // the preset is validated against the clients central has cached, which a notification refreshes after the commit
        clients.refreshNow *> presets.savePresets(SaveAuthorizationPresetsRequest(
          clientId = client.id,
          presets = List(AuthorizationPresetInput(
            id = PresetId(client.id),
            description = client.clientName.getOrElse("en", client.id),
            redirectUri = redirect,
            postLoginRedirectUri = redirect,
            postLogoutRedirectUri = None,
            scope = client.scope,
            responseType = ResponseType.Code,
            uiLocales = None,
            customParameters = Map.empty,
            cookieDomain = None,
            cookiePath = None,
          )),
        )).flatMap:
          case Right(()) => ZIO.unit
          case Left(error) => ZIO.fail(PartnerError.Conflict(s"the login preset of client '${client.id}' cannot be created: $error"))
