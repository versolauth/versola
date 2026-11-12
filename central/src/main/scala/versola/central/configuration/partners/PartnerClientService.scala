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

  /** What an approval will register, before anything is decided: `overrides` laid over `requested`, as `applied` when it
    * differs from `requested`. Only the fields a partner may ask for can be overridden (not `id`, `kind` or `authMethod`),
    * and the result must still be a registration of the client the approval is about. */
  def checkOverrides(approval: PartnerApprovalRecord, overrides: Option[Json.Obj]): IO[PartnerError, Option[Json.Obj]]

  /** Applies an approval the operator has just won (it is decided in the database first, so that two decisions on one
    * request cannot both apply): `applied` laid over `requested` is what gets registered. A `client_create` sets the client
    * `approved` and gives it its preset (`/login/<client id>`). */
  def applyApproval(approval: PartnerApprovalRecord, applied: Option[Json.Obj]): IO[PartnerError | Throwable, Unit]

  /** Puts a client back to what it was before [[applyApproval]] when applying failed: a `client_create` is `pending` again, so
    * that a failure never leaves a client that is served and whose approval is not decided. */
  def revertApproval(approval: PartnerApprovalRecord): Task[Unit]

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

  /** What the operator's users see and agree to on the consent screen: the name and logo, the links to the partner's policy and
    * terms, and how consent is asked for. A change of any of them always needs the operator (consent phishing, weakened consent). */
  val ConsentScreenFields: List[String] = List("clientName", "logoUri", "policyUri", "tosUri", "consentFlow")

  /** The fields of a request the operator may override when approving it. The identity and the credential of the client are
    * not among them: they are what the approval is about. */
  val OverridableFields: Set[String] = Set(
    "clientName",
    "logoUri",
    "policyUri",
    "tosUri",
    "redirectUris",
    "scope",
    "jwks",
    "accessTokenTtl",
    "refreshTokenTtl",
    "backChannelLogoutUri",
    "consentFlow",
  )

  /** How many of a partner's requests a listing reads: the newest ones, which is where the waiting ones and the latest
    * decisions are. */
  val HistoryLimit: Int = 500

  /** Whether a failure of a write is one of the triggers that enforce a partner's limits, which name themselves in the message of
    * the exception (`partner_client_limit`, `partner_edge_limit`), however deep in the causes the database driver put it. */
  def isLimitViolation(error: Throwable, marker: String): Boolean =
    Option(error.getMessage).exists(_.contains(marker)) || Option(error.getCause).exists(isLimitViolation(_, marker))

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
        // the count of clients is enforced by the table (a trigger that locks the partner), where two creations cannot both pass
        case error: Throwable if PartnerClientService.isLimitViolation(error, "partner_client_limit") =>
          ZIO.fail(PartnerError.Invalid("the partner has registered as many clients as its limits allow"))
        case error: Throwable => ZIO.fail(error)

    private def owned(partner: PartnerRecord, clientId: ClientId): IO[PartnerError | Throwable, OAuthClientRecord] =
      clients.findClientFresh(clientId).flatMap:
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
        // `<partner>-console` is the name of a console client, and a client and a preset share one namespace of ids: another partner's
        // console may not be registered ahead of its partner
        _ <- ZIO.fail(
          PartnerError.Invalid(s"a client id may not end in '-console': those are the partners' consoles"),
        ).when(request.id.endsWith("-console"))
        registration = request.toCreateClientRequest(partner)
        _ <- clients.registerClient(registration, approval = ClientApproval.Pending, allowOutsideLimits = true).catchAll(invalid)
        client <- clients.findClientFresh(request.id).someOrFail(IllegalStateException(s"client '${request.id}' was registered and cannot be read"))
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
        // what makes it no partner's client at all (a back-channel address outside the partner's domains) is refused, not queued
        _ <- ZIO.foreachDiscard(PartnerClientRules.invariant(request.id, partner, patched))(error => invalid(error))
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
        consentFlow = subject.consentFlow || request.consentFlow.isDefined,
        backChannelLogoutUri = request.backChannelLogoutUri match
          case Some(Patch.Modified(uri)) => Some(uri)
          case Some(Patch.Deleted) => None
          case _ => subject.backChannelLogoutUri,
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
        _ <- owned(partner, clientId)
        waiting <- approvals.waitingFor(
          partner.id,
          clientId,
        ).map(_.headOption).someOrFail(PartnerError.NotFound(s"a waiting request about client '$clientId'"))
        now <- Clock.instant
        // Decided first, and only the one that wins goes on: an operator who approved in between has decided it, and an
        // approved client is not to be deleted by a withdrawal that lost.
        won <- approvals.decide(waiting.id, ApprovalStatus.Withdrawn, None, now, None, None)
        _ <- ZIO.fail(PartnerError.Conflict(s"the request about client '$clientId' was decided meanwhile")).unless(won)
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
        requests <- approvals.list(None, None, Some(partner.id), PartnerClientService.HistoryLimit)
      yield own.map(client => View(client, stateOf(client, requests.filter(_.targetId == client.id))))

    override def find(partner: PartnerRecord, clientId: ClientId): Task[Option[View]] =
      for
        own <- clients.findClientFresh(clientId).map(_.filter(c => c.partnerId.contains(partner.id) && c.id != partner.consoleClientId))
        requests <- approvals.forTarget(partner.id, clientId, PartnerClientService.HistoryLimit)
      yield own.map(client => View(client, stateOf(client, requests)))

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

    private def decode[A: JsonDecoder](json: Json.Obj, what: String): IO[PartnerError, A] =
      ZIO.fromEither(json.toJson.fromJson[A]).mapError(reason => PartnerError.Invalid(s"the $what cannot be read: $reason"))

    override def checkOverrides(approval: PartnerApprovalRecord, overrides: Option[Json.Obj]): IO[PartnerError, Option[Json.Obj]] =
      overrides.filter(_.fields.nonEmpty) match
        case None => ZIO.none
        case Some(overridden) =>
          val forbidden = overridden.fields.map(_._1).filterNot(PartnerClientService.OverridableFields.contains)
          for
            _ <- ZIO.fail(
              PartnerError.Invalid(s"${forbidden.mkString(", ")} cannot be overridden: what the approval is about is not the operator's to change"),
            )
              .when(forbidden.nonEmpty)
            _ <- ZIO.fail(PartnerError.Invalid("a profile change has no client fields to override")).when(approval.kind == ApprovalKind.ProfileChange)
            applied = merge(approval.requested, overridden)
            // read as what it will be applied as, so that an override that cannot be read refuses before anything is decided
            _ <- approval.kind match
              case ApprovalKind.ClientCreate => decode[PartnerClientRequest](applied, "approved registration").unit
              case _ => decode[PartnerClientUpdate](applied, "approved change").unit
          yield Some(applied)

    override def applyApproval(approval: PartnerApprovalRecord, applied: Option[Json.Obj]): IO[PartnerError | Throwable, Unit] =
      val clientId = ClientId(approval.targetId)
      for
        // as stored now: the preset and the change are computed from what is there, not from a cache that has not seen the
        // last write
        client <- clients.findClientFresh(clientId).someOrFail(PartnerError.NotFound(s"client '$clientId'"))
        _ <- ZIO.fail(PartnerError.NotFound(s"client '$clientId'")).unless(client.partnerId.contains(approval.partnerId))
        _ <- approval.kind match
          case ApprovalKind.ClientCreate =>
            for
              // the overrides are a change to what the partner registered: read as one, so the same rules apply
              _ <- ZIO.foreachDiscard(applied): json =>
                decode[PartnerClientRequest](json, "approved registration").flatMap: request =>
                  clients.updateClient(
                    asUpdate(request.copy(id = clientId)).toUpdateClientRequest(client),
                    allowOutsideLimits = true,
                  ).catchAll(invalid)
              _ <- clients.setApproval(clientId, ClientApproval.Approved)
              approved <- clients.findClientFresh(clientId).someOrFail(PartnerError.NotFound(s"client '$clientId'"))
              _ <- loginPreset(approved)
            yield ()
          case ApprovalKind.ClientChange =>
            decode[PartnerClientUpdate](applied.getOrElse(approval.requested), "approved change").flatMap: request =>
              clients.updateClient(request.copy(id = clientId).toUpdateClientRequest(client), allowOutsideLimits = true).catchAll(invalid)
          case ApprovalKind.ProfileChange =>
            ZIO.fail(PartnerError.Invalid("a profile change is not a client request"))
      yield ()

    override def revertApproval(approval: PartnerApprovalRecord): Task[Unit] =
      approval.kind match
        case ApprovalKind.ClientCreate => clients.setApproval(ClientId(approval.targetId), ClientApproval.Pending).unit
        case _ => ZIO.unit

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
