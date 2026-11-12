package versola.central.configuration.partners

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.{EdgeCertificateRepository, EdgeGroupId, EdgeId, EdgeResponse}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic}
import versola.util.RedirectUri
import versola.util.audit.AuditContext
import versola.util.http.Observability
import zio.http.*
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*
import zio.{IO, UIO, ZIO}

import java.time.Instant

/** The operator's side of partners (#962 §3), on the main listener: the registry, the kill switch, the limits and the queue of
  * requests. Query parameters, not path parameters, as every route of central. */
object PartnerController extends AuditedController:
  /** How many requests the operator's queue shows unless it asks for fewer or more (`limit`), and the most it can ask for. */
  val DefaultApprovalsLimit: Int = 100
  val MaxApprovalsLimit: Int = 500

  type Env = Tracing & PartnerService & PartnerRegistry & ResourceService & EdgeCertificateRepository & CentralConfig

  def endpoints = List(
    listEndpoint,
    createEndpoint,
    updateEndpoint,
    suspendEndpoint,
    resumeEndpoint,
    limitsEndpoint,
    approvalsEndpoint,
    approveEndpoint,
    rejectEndpoint,
  )

  val listEndpoint =
    (Method.GET / "configuration" / "partners").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        tenantId <- request.url.queryZIO[Option[TenantId]]("tenantId")
        partners <- ZIO.serviceWithZIO[PartnerRegistry](_.getAll)
        listed = tenantId.fold(partners)(id => partners.filter(_.tenantId == id))
      yield Response.json(GetPartnersResponse(listed.map(PartnerResponse.of)).toJson)
    }

  /** One request creates everything a partner starts with; the token and the signing key in the answer are shown once. */
  val createEndpoint =
    (Method.POST / "configuration" / "partners").audited(Audit.change("partner", Audit.create, revealsSecret = true)) -> handler {
      (request: Request) =>
        (for
          _ <- authorizeBasic(request)
          body <- request.bodyAs[CreatePartnerRequest]
          _ <- Audit.target(body.partner.id)
          _ <- Audit.tenant(body.partner.tenantId)
          _ <- Audit.partner(body.partner.id)
          created <- ZIO.serviceWithZIO[PartnerService](_.create(PartnerService.NewPartner(
            id = body.partner.id,
            tenantId = body.partner.tenantId,
            displayName = body.partner.displayName,
            logoUri = body.partner.logoUri,
            contact = body.partner.contact,
            limits = body.partner.limits,
            reviewAt = body.partner.reviewAt,
            consoleRedirectUri = body.consoleClient.redirectUri,
            firstEdgeId = body.firstEdge.id,
            firstEdgePublicUrl = body.firstEdge.publicUrl,
          )))
          certificates <- ZIO.serviceWithZIO[EdgeCertificateRepository](_.latest)
          response = CreatedPartnerResponse(
            partner = PartnerResponse.of(created.partner),
            edge = EdgeResponse.of(created.edge.edge, Some(created.partner.id), certificates.get(created.edge.edge.id)),
            enrollmentToken = created.edge.token.token,
            expiresAt = created.edge.token.expiresAt,
            bundle = created.edge.bundle,
          )
        yield Response.json(response.toJson).status(Status.Created)).catchAll(failure)
    }

  val updateEndpoint =
    (Method.PUT / "configuration" / "partners").audited(Audit.change("partner", Audit.update)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[UpdatePartnerRequest]
        _ <- Audit.target(body.id)
        _ <- Audit.partner(body.id)
        _ <- Audit.changed(body, "id")
        _ <- ZIO.serviceWithZIO[PartnerService](_.updateProfile(body.id, body.displayName, body.logoUri, body.contact, body.reviewAt))
      yield Response.status(Status.NoContent)).catchAll(failure)
    }

  /** The kill switch (#962 §5). `note` is the operator's word to the record of the request; it is not stored in P0. */
  val suspendEndpoint =
    (Method.POST / "configuration" / "partners" / "suspend").audited(Audit.change("partner", Audit.action("suspend"))) -> handler {
      (request: Request) =>
        (for
          _ <- authorizeBasic(request)
          body <- request.bodyAs[SuspendPartnerRequest]
          _ <- Audit.target(body.id)
          _ <- Audit.partner(body.id)
          // the log keeps the names of the fields, never their values: that a note came with it, not what it says
          _ <- Audit.changedFields("note" -> body.note.exists(_.nonEmpty))
          outcome <- ZIO.serviceWithZIO[PartnerService](_.suspend(body.id))
        // The partner is suspended either way. `204` when auth was told to reload its clients; `207` when it could not be, so that
        // the operator can call again once auth answers.
        yield
          if outcome.authSynced then Response.status(Status.NoContent)
          else Response.json(SuspendPartnerResponse(authSynced = false).toJson).status(Status.MultiStatus)).catchAll(
          failure,
        )
    }

  val resumeEndpoint =
    (Method.POST / "configuration" / "partners" / "resume").audited(Audit.change("partner", Audit.action("resume"))) -> handler {
      (request: Request) =>
        (for
          _ <- authorizeBasic(request)
          body <- request.bodyAs[ResumePartnerRequest]
          _ <- Audit.target(body.id)
          _ <- Audit.partner(body.id)
          _ <- ZIO.serviceWithZIO[PartnerService](_.resume(body.id))
        yield Response.status(Status.NoContent)).catchAll(failure)
    }

  /** New limits; a client of the partner now outside them is named in the answer and left as it is. */
  val limitsEndpoint =
    (Method.PUT / "configuration" / "partners" / "limits").audited(Audit.change("partner", Audit.update)) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[SetPartnerLimitsRequest]
        _ <- Audit.target(body.id)
        _ <- Audit.partner(body.id)
        _ <- Audit.changedFields("limits" -> true)
        outside <- ZIO.serviceWithZIO[PartnerService](_.setLimits(body.id, body.limits))
      yield Response.json(SetPartnerLimitsResponse(outside.map(c => ClientOutsideLimitsResponse(c.clientId, c.fields))).toJson)).catchAll(failure)
    }

  val approvalsEndpoint =
    (Method.GET / "configuration" / "partners" / "approvals").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        tenantId <- request.url.queryZIO[Option[TenantId]]("tenantId")
        partnerId <- request.url.queryZIO[Option[PartnerId]]("partnerId")
        status <- request.url.queryZIO[Option[ApprovalStatus]]("status")
        limit <- request.url.queryZIO[Option[Int]]("limit")
        approvals <- ZIO.serviceWithZIO[PartnerService](
          _.approvals(
            tenantId,
            status,
            partnerId,
            limit.fold(PartnerController.DefaultApprovalsLimit)(_.max(1).min(PartnerController.MaxApprovalsLimit)),
          ),
        )
      yield Response.json(GetPartnerApprovalsResponse(approvals.map(PartnerApprovalResponse.of)).toJson)
    }

  /** Approves as asked, or with `overrides` laid over what the partner asked; the result is kept as `applied`. */
  val approveEndpoint =
    (Method.POST / "configuration" / "partners" / "approvals" / "approve").audited(Audit.change("partner-approval", Audit.action("approve"))) ->
      handler { (request: Request) =>
        (for
          _ <- authorizeBasic(request)
          body <- request.bodyAs[ApproveRequest]
          _ <- Audit.targetByText(body.id)
          decidedBy <- actor
          approved <- ZIO.serviceWithZIO[PartnerService](_.approve(body.id, decidedBy, body.note, body.overrides))
          _ <- Audit.partner(approved.partnerId)
        yield Response.json(PartnerApprovalResponse.of(approved).toJson)).catchAll(failure)
      }

  val rejectEndpoint =
    (Method.POST / "configuration" / "partners" / "approvals" / "reject").audited(Audit.change("partner-approval", Audit.action("reject"))) ->
      handler { (request: Request) =>
        (for
          _ <- authorizeBasic(request)
          body <- request.bodyAs[RejectRequest]
          _ <- Audit.targetByText(body.id)
          decidedBy <- actor
          rejected <- ZIO.serviceWithZIO[PartnerService](_.reject(body.id, decidedBy, body.note))
          _ <- Audit.partner(rejected.partnerId)
        yield Response.json(PartnerApprovalResponse.of(rejected).toJson)).catchAll(failure)
      }

  /** The administrator the request came from, as the audit hook found them: the `sub` recorded as `decidedBy`. */
  private val actor: UIO[Option[String]] = AuditContext.current.map(_.actor.flatMap(_._2))

  private def failure(error: PartnerError | Throwable): IO[Throwable, Response] =
    error match
      case PartnerError.NotFound(what) =>
        Observability.setError("partner_not_found", Some(what)).as(Response.text(s"$what does not exist").status(Status.NotFound))
      case PartnerError.Invalid(reason) => Observability.setError("partner_invalid", Some(reason)).as(Response.text(reason).status(Status.BadRequest))
      case PartnerError.Conflict(reason) => Observability.setError("partner_conflict", Some(reason)).as(Response.text(reason).status(Status.Conflict))
      case error: Throwable => ZIO.fail(error)

/** A partner as the console shows it. `settings` are the partner's own and are not here. */
case class PartnerResponse(
    id: PartnerId,
    tenantId: TenantId,
    displayName: String,
    logoUri: Option[String],
    contact: PartnerContact,
    status: PartnerStatus,
    limits: PartnerLimits,
    groupId: EdgeGroupId,
    consoleClientId: ClientId,
    reviewAt: Option[Instant],
    createdAt: Instant,
) derives Schema, JsonCodec

object PartnerResponse:
  def of(record: PartnerRecord): PartnerResponse =
    PartnerResponse(
      id = record.id,
      tenantId = record.tenantId,
      displayName = record.displayName,
      logoUri = record.logoUri,
      contact = record.contact,
      status = record.status,
      limits = record.limits,
      groupId = record.groupId,
      consoleClientId = record.consoleClientId,
      reviewAt = record.reviewAt,
      createdAt = record.createdAt,
    )

case class GetPartnersResponse(partners: Vector[PartnerResponse]) derives Schema, JsonCodec

case class NewPartnerRequest(
    id: PartnerId,
    tenantId: TenantId,
    displayName: String,
    logoUri: Option[String],
    contact: PartnerContact,
    limits: PartnerLimits,
    reviewAt: Option[Instant],
) derives Schema, JsonCodec

/** Where the partner's console completes its sign-in: the one redirect URI of the console client. */
case class ConsoleClientRequest(redirectUri: RedirectUri) derives Schema, JsonCodec

case class FirstEdgeRequest(id: EdgeId, publicUrl: String) derives Schema, JsonCodec

case class CreatePartnerRequest(
    partner: NewPartnerRequest,
    consoleClient: ConsoleClientRequest,
    firstEdge: FirstEdgeRequest,
) derives Schema, JsonCodec

/** What the operator hands to the partner: the token and the bundle are shown here and never again. */
case class CreatedPartnerResponse(
    partner: PartnerResponse,
    edge: EdgeResponse,
    enrollmentToken: String,
    expiresAt: Instant,
    bundle: String,
) derives Schema, JsonCodec

case class UpdatePartnerRequest(
    id: PartnerId,
    displayName: String,
    logoUri: Option[String],
    contact: PartnerContact,
    reviewAt: Option[Instant],
) derives Schema, JsonCodec

case class SuspendPartnerRequest(id: PartnerId, note: Option[String]) derives Schema, JsonCodec

/** The answer of a suspension whose auth could not be told to reload (`207`): the partner is suspended all the same. */
case class SuspendPartnerResponse(authSynced: Boolean) derives Schema, JsonCodec

case class ResumePartnerRequest(id: PartnerId) derives Schema, JsonCodec

case class SetPartnerLimitsRequest(id: PartnerId, limits: PartnerLimits) derives Schema, JsonCodec

case class ClientOutsideLimitsResponse(clientId: ClientId, fields: List[String]) derives Schema, JsonCodec

case class SetPartnerLimitsResponse(outsideLimits: Vector[ClientOutsideLimitsResponse]) derives Schema, JsonCodec

case class PartnerApprovalResponse(
    id: ApprovalId,
    partnerId: PartnerId,
    kind: ApprovalKind,
    targetId: String,
    requested: Json.Obj,
    current: Option[Json.Obj],
    applied: Option[Json.Obj],
    outsideLimits: List[String],
    requestedBy: String,
    requestedAt: Instant,
    status: ApprovalStatus,
    decidedBy: Option[String],
    decidedAt: Option[Instant],
    note: Option[String],
) derives JsonCodec

object PartnerApprovalResponse:
  def of(record: PartnerApprovalRecord): PartnerApprovalResponse =
    PartnerApprovalResponse(
      id = record.id,
      partnerId = record.partnerId,
      kind = record.kind,
      targetId = record.targetId,
      requested = record.requested,
      current = record.current,
      applied = record.applied,
      outsideLimits = record.outsideLimits,
      requestedBy = record.requestedBy,
      requestedAt = record.requestedAt,
      status = record.status,
      decidedBy = record.decidedBy,
      decidedAt = record.decidedAt,
      note = record.note,
    )

case class GetPartnerApprovalsResponse(approvals: Vector[PartnerApprovalResponse]) derives JsonCodec

case class ApproveRequest(id: ApprovalId, note: Option[String], overrides: Option[Json.Obj]) derives JsonCodec

case class RejectRequest(id: ApprovalId, note: String) derives JsonCodec
