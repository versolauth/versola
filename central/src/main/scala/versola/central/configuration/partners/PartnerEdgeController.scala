package versola.central.configuration.partners

import versola.central.audit.{Audit, AuditCursor, CentralAudit, CentralAuditRepository}
import versola.central.configuration.clients.{
  AuthorizationPresetService,
  ClientController,
  ClientId,
  OAuthClientRecord,
  OAuthClientService,
  PartnerClientRules,
}
import versola.central.configuration.edges.{
  EdgeAccessLogSyncResponse,
  EdgeCertificateRepository,
  EdgeDpopSyncResponse,
  EdgeEnrollmentTokenRepository,
  EdgeId,
  EdgeOverloadSyncResponse,
  EdgeRateLimitSyncResponse,
  EdgeRecord,
  EdgeResponse,
  EdgeService,
  EdgeSyncController,
}
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.central.configuration.resources.AudienceRateLimit
import versola.central.configuration.resources.{ResourceController, ResourceId, ResourceRecord, ResourceService}
import versola.central.configuration.tenants.TenantRepository
import versola.central.configuration.{
  CreateResourceEndpointRequest,
  CreateResourceRequest,
  CreateResourceResponse,
  GetResourcesSyncResponse,
  ResourceResponse,
  ResourceUri,
  UpdateResourceRequest,
}
import versola.central.{CentralConfig, PartnerCaller, authorizePartnerEdge}
import versola.util.audit.{ActorType, AuditContext, AuditDetails, AuditDraft, AuditEvent, AuditEvents, AuditOutcome}
import versola.util.http.{Controller, Forbidden, Observability, Unauthorized}
import versola.util.{Base64Url, JsonWebKeySet, SecurityService}
import zio.http.*
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec, JsonEncoder}
import zio.schema.*
import zio.{IO, Task, UIO, ZIO}

import java.sql.SQLException
import java.time.Instant

/** The surface a partner's edge reaches (#549, #962 §4), on central's mutual-TLS listener next to `/edge/sync`: what the
  * edge reads (`/edge/partner/me`, `/edge/partner/sync/...`) and what it changes on behalf of its console. Every route
  * authenticates the caller with [[authorizePartnerEdge]] and answers for that partner only: its own clients, resources,
  * presets, edges and audit trail, nothing of another partner's and nothing of the operator's. A management route carries
  * the staff member's token in `Versola-Principal` besides ([[PartnerPrincipal]]), and every change writes an audit event
  * with the partner and the edge. */
object PartnerEdgeController extends Controller:
  type Env = Tracing & CentralConfig & EdgeService & PartnerRegistry & PartnerService & PartnerClientService & OAuthClientService &
    ResourceService & AuthorizationPresetService & JwksService & ServerMetadataService & SecurityService & TenantRepository &
    EdgeCertificateRepository & EdgeEnrollmentTokenRepository & CentralAuditRepository & AuditEvents

  def routes: Routes[Env, Throwable] = Routes(
    meEndpoint,
    syncClientsEndpoint,
    syncResourcesEndpoint,
    syncPresetsEndpoint,
    syncJwksEndpoint,
    syncDpopEndpoint,
    syncOverloadEndpoint,
    syncAccessLogEndpoint,
    syncRateLimitsEndpoint,
    syncServerMetadataEndpoint,
    listClientsEndpoint,
    createClientEndpoint,
    updateClientEndpoint,
    deleteClientEndpoint,
    withdrawClientEndpoint,
    replaceClientKeysEndpoint,
    listResourcesEndpoint,
    createResourceEndpoint,
    updateResourceEndpoint,
    deleteResourceEndpoint,
    rotateResourceSecretEndpoint,
    listEdgesEndpoint,
    createEdgeEndpoint,
    deleteEdgeEndpoint,
    createEnrollmentTokenEndpoint,
    revokeEnrollmentTokenEndpoint,
    profileEndpoint,
    getSettingsEndpoint,
    putSettingsEndpoint,
    auditEndpoint,
  )

  private val Prefix = Method.GET / "edge" / "partner"

  // ── what the edge reads ────────────────────────────────────────────────────────────────────────

  /** Who the caller is to central, and where everything else is. The first call of an `onboarding` partner's edge makes the
    * partner `active`: its edge is there. */
  val meEndpoint =
    Method.GET / "edge" / "partner" / "me" -> read { (caller, _) =>
      for
        _ <- ZIO.serviceWithZIO[PartnerService](_.activate(caller.partner))
        partner <- ZIO.serviceWithZIO[PartnerRegistry](_.find(caller.partner.id)).map(_.getOrElse(caller.partner))
        config <- ZIO.service[CentralConfig]
        tenant <- ZIO.serviceWithZIO[TenantRepository](_.getAll).map(_.find(_.id == partner.tenantId))
        metadata <- ZIO.serviceWithZIO[ServerMetadataService](_.getMetadata)
        issuer = metadata.flatMap(_.get("issuer")).collect { case Json.Str(value) => value }
        response = MeResponse(
          partner = MePartner(
            id = partner.id,
            displayName = partner.displayName,
            logoUri = partner.logoUri,
            status = partner.status,
            limits = partner.limits,
            reviewAt = partner.reviewAt,
            tenantId = partner.tenantId,
          ),
          operator = MeOperator(
            name = tenant.map(_.description).getOrElse(partner.tenantId),
            issuer = issuer,
            mtlsUrl = config.auth.mtlsUrl.map(_.encode),
            centralMtlsUrl = config.publicUrls.map(_.mtls.encode),
          ),
          console = MeConsole(clientId = partner.consoleClientId, presetId = partner.consoleClientId),
          edge = MeEdge(id = caller.edge.id, publicUrl = caller.edge.publicUrl),
        )
      yield Response.json(response.toJson)
    }

  /** The partner's approved clients, without the credentials the first-party sync carries ([[ClientController.partnerClientsSyncResponse]]). */
  val syncClientsEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "clients" -> read { (caller, _) =>
      approvedClients(caller.partner).map(clients => Response.json(ClientController.partnerClientsSyncResponse(clients).toJson))
    }

  val syncResourcesEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "resources" -> read { (caller, _) =>
      for
        resources <- ownResources(caller.partner)
        encrypted <- ZIO.foreach(resources)(EdgeSyncController.toResourceSyncResponse(_, caller.edge))
      yield Response.json(GetResourcesSyncResponse(encrypted).toJson)
    }

  /** The presets of the partner's clients: the console's, and the one each approved client got on approval. */
  val syncPresetsEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "auth-request-presets" -> read { (caller, _) =>
      for
        clients <- approvedClients(caller.partner)
        presets <- ZIO.serviceWithZIO[AuthorizationPresetService](_.getPresetsForSync(None))
        own = clients.map(_.id).toSet
      yield Response.json(EdgeSyncController.presetsSyncResponse(presets.filter(preset => own.contains(preset.clientId))).toJson)
    }

  val syncJwksEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "jwks" -> read { (_, _) =>
      ZIO.serviceWithZIO[JwksService](_.getRaw).map(jwks => Response.json(jwks.toJson))
    }

  val syncDpopEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "dpop" -> read { (caller, _) =>
      ZIO.succeed(Response.json(EdgeDpopSyncResponse(requireDpopNonce = caller.edge.requireDpopNonce).toJson))
    }

  val syncOverloadEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "overload" -> read { (caller, _) =>
      ZIO.succeed(Response.json(EdgeOverloadSyncResponse(overloadPolicy = caller.edge.overloadPolicy).toJson))
    }

  val syncAccessLogEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "access-log" -> read { (caller, _) =>
      ZIO.succeed(Response.json(EdgeAccessLogSyncResponse(accessLogPolicy = caller.edge.accessLogPolicy).toJson))
    }

  val syncRateLimitsEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "rate-limits" -> read { (caller, _) =>
      ZIO.succeed(Response.json(EdgeRateLimitSyncResponse(rateLimitPolicies = caller.edge.rateLimitPolicies).toJson))
    }

  val syncServerMetadataEndpoint =
    Method.GET / "edge" / "partner" / "sync" / "server-metadata" -> read { (_, _) =>
      EdgeSyncController.reducedServerMetadata.map(reduced => Response.json(reduced.toJson))
    }

  // ── what the edge changes for its console ──────────────────────────────────────────────────────

  val listClientsEndpoint =
    Method.GET / "edge" / "partner" / "clients" -> managed("partner-client", None, "/edge/partner/clients") { (caller, _, request) =>
      for
        id <- request.url.queryZIO[Option[ClientId]]("id")
        views <- ZIO.serviceWithZIO[PartnerClientService](_.list(caller.partner))
        response <- id match
          case None => ZIO.succeed(Response.json(PartnerClientsResponse(views.map(PartnerClientResponse.of)).toJson))
          case Some(id) =>
            views.find(_.client.id == id) match
              case Some(view) => ZIO.succeed(Response.json(PartnerClientResponse.of(view).toJson))
              case None => ZIO.fail(PartnerError.NotFound(s"client '$id'"))
      yield response
    }

  /** A new client: registered `pending` and answered `202` with its request, which waits for the operator. */
  val createClientEndpoint =
    Method.POST / "edge" / "partner" / "clients" -> managed("partner-client", Some(Audit.create), "/edge/partner/clients") {
      (caller, principal, request) =>
        for
          body <- request.bodyAs[PartnerClientRequest]
          _ <- Audit.target(body.id)
          _ <- ZIO.serviceWithZIO[PartnerClientService](_.create(caller.partner, body, principal.sub))
          view <- ZIO.serviceWithZIO[PartnerClientService](_.find(caller.partner, body.id)).someOrFail(PartnerError.NotFound(s"client '${body.id}'"))
        yield Response.json(PartnerClientResponse.of(view).toJson).status(Status.Accepted)
    }

  /** Inside the limits and away from the consent screen the change applies (`204`); otherwise it waits (`202`). */
  val updateClientEndpoint =
    Method.PUT / "edge" / "partner" / "clients" -> managed("partner-client", Some(Audit.update), "/edge/partner/clients") {
      (caller, principal, request) =>
        for
          body <- request.bodyAs[PartnerClientUpdate]
          _ <- Audit.target(body.id)
          _ <- Audit.changed(body, "id")
          result <- ZIO.serviceWithZIO[PartnerClientService](_.update(caller.partner, body, principal.sub))
          response <- result match
            case Right(()) => ZIO.succeed(Response.status(Status.NoContent))
            case Left(_) =>
              ZIO.serviceWithZIO[PartnerClientService](_.find(caller.partner, body.id)).someOrFail(PartnerError.NotFound(s"client '${body.id}'"))
                .map(view => Response.json(PartnerClientResponse.of(view).toJson).status(Status.Accepted))
        yield response
    }

  val deleteClientEndpoint =
    Method.DELETE / "edge" / "partner" / "clients" -> managed("partner-client", Some(Audit.delete), "/edge/partner/clients") {
      (caller, _, request) =>
        for
          id <- request.url.queryZIO[ClientId]("id")
          _ <- Audit.target(id)
          _ <- ZIO.serviceWithZIO[PartnerClientService](_.delete(caller.partner, id))
        yield Response.status(Status.NoContent)
    }

  val withdrawClientEndpoint =
    Method.POST / "edge" / "partner" / "clients" / "withdraw" -> managed(
      "partner-client",
      Some(Audit.action("withdraw")),
      "/edge/partner/clients/withdraw",
    ) {
      (caller, _, request) =>
        for
          body <- request.bodyAs[ClientIdRequest]
          _ <- Audit.target(body.id)
          _ <- ZIO.serviceWithZIO[PartnerClientService](_.withdraw(caller.partner, body.id))
        yield Response.status(Status.NoContent)
    }

  val replaceClientKeysEndpoint =
    Method.POST / "edge" / "partner" / "clients" / "jwks" -> managed("partner-client", Some(Audit.action("jwks")), "/edge/partner/clients/jwks") {
      (caller, _, request) =>
        for
          body <- request.bodyAs[ReplaceClientKeysRequest]
          _ <- Audit.target(body.id)
          _ <- ZIO.serviceWithZIO[PartnerClientService](_.replaceKeys(caller.partner, body.id, body.jwks))
        yield Response.status(Status.NoContent)
    }

  val listResourcesEndpoint =
    Method.GET / "edge" / "partner" / "resources" -> managed("partner-resource", None, "/edge/partner/resources") { (caller, _, _) =>
      ownResources(caller.partner).map(resources =>
        Response.json(PartnerResourcesResponse(resources.map(ResourceController.toResourceResponse)).toJson),
      )
    }

  /** A resource of the partner, always internal: the partner's edge authenticates to the upstream with the secret, shown once. */
  val createResourceEndpoint =
    Method.POST / "edge" / "partner" / "resources" -> managed("partner-resource", Some(Audit.create), "/edge/partner/resources") {
      (caller, _, request) =>
        for
          body <- request.bodyAs[PartnerResourceRequest]
          _ <- Audit.target(body.resourceId)
          _ <- resourceUriAllowed(caller.partner, body.resource)
          _ <- audienceOwned(caller.partner, body.audience)
          result <- ZIO.serviceWithZIO[ResourceService](_.createResource(CreateResourceRequest(
            tenantId = caller.partner.tenantId,
            resourceId = body.resourceId,
            resource = body.resource,
            audience = body.audience,
            endpoints = body.endpoints,
            internal = true,
            audienceRateLimits = body.audienceRateLimits,
            partnerId = Some(caller.partner.id),
          ))).catchSome {
            case error: SQLException if error.getSQLState == "23505" =>
              ZIO.fail(PartnerError.Conflict(s"resource '${body.resource}' exists in the tenant"))
          }
          response <- result match
            case Right((resourceId, secret)) =>
              ZIO.succeed(Response.json(CreateResourceResponse(resourceId, secret.map(Base64Url.encode)).toJson).status(Status.Created))
            case Left(error) => ZIO.fail(PartnerError.Invalid(error.toString.take(500)))
        yield response
    }

  val updateResourceEndpoint =
    Method.PUT / "edge" / "partner" / "resources" -> managed("partner-resource", Some(Audit.update), "/edge/partner/resources") {
      (caller, _, request) =>
        for
          body <- request.bodyAs[UpdateResourceRequest]
          _ <- Audit.target(body.resourceId)
          _ <- Audit.changed(body, "resourceId")
          current <- ownResource(caller.partner, body.resourceId)
          _ <- ZIO.foreachDiscard(body.resource)(resourceUriAllowed(caller.partner, _))
          _ <- audienceOwned(caller.partner, body.audience.patch(current.audience))
          result <- ZIO.serviceWithZIO[ResourceService](_.updateResource(body)).catchSome {
            // a new `resource` that another resource of the tenant already has
            case error: SQLException if error.getSQLState == "23505" => ZIO.fail(PartnerError.Conflict("that resource exists in the tenant"))
          }
          response <- result match
            case Right(()) => ZIO.succeed(Response.status(Status.NoContent))
            case Left(error) => ZIO.fail(PartnerError.Invalid(error.toString.take(500)))
        yield response
    }

  val deleteResourceEndpoint =
    Method.DELETE / "edge" / "partner" / "resources" -> managed("partner-resource", Some(Audit.delete), "/edge/partner/resources") {
      (caller, _, request) =>
        for
          id <- request.url.queryZIO[ResourceId]("resourceId")
          _ <- Audit.target(id)
          _ <- ownResource(caller.partner, id)
          _ <- ZIO.serviceWithZIO[ResourceService](_.deleteResource(id))
        yield Response.status(Status.NoContent)
    }

  val rotateResourceSecretEndpoint =
    Method.POST / "edge" / "partner" / "resources" / "rotate-secret" ->
      managed("partner-resource", Some(Audit.action("rotate-secret")), "/edge/partner/resources/rotate-secret") { (caller, _, request) =>
        for
          body <- request.bodyAs[ResourceIdRequest]
          _ <- Audit.target(body.resourceId)
          _ <- ownResource(caller.partner, body.resourceId)
          secret <- ZIO.serviceWithZIO[ResourceService](_.rotateSecret(body.resourceId)).catchSome {
            case ResourceService.SecretRotationInProgress => ZIO.fail(PartnerError.Conflict("a rotation of the secret is already in progress"))
          }
        yield Response.json(RotatedSecretResponse(Base64Url.encode(secret)).toJson)
      }

  val listEdgesEndpoint =
    Method.GET / "edge" / "partner" / "edges" -> managed("partner-edge", None, "/edge/partner/edges") { (caller, _, _) =>
      for
        edges <- ZIO.serviceWithZIO[PartnerService](_.edges(caller.partner))
        certificates <- ZIO.serviceWithZIO[EdgeCertificateRepository](_.latest)
      yield Response.json(PartnerEdgesResponse(edges.map(edge => EdgeResponse.of(edge, Some(caller.partner.id), certificates.get(edge.id)))).toJson)
    }

  /** Another edge of the partner, within `maxEdges`: the edge, its signing key, its token and its bundle in one answer. */
  val createEdgeEndpoint =
    Method.POST / "edge" / "partner" / "edges" -> managed("partner-edge", Some(Audit.create), "/edge/partner/edges") { (caller, _, request) =>
      for
        body <- request.bodyAs[CreatePartnerEdgeRequest]
        _ <- Audit.target(body.id)
        enrolled <- ZIO.serviceWithZIO[PartnerService](_.addEdge(caller.partner, body.id, body.publicUrl))
        certificates <- ZIO.serviceWithZIO[EdgeCertificateRepository](_.latest)
        response = CreatedPartnerEdgeResponse(
          edge = EdgeResponse.of(enrolled.edge, Some(caller.partner.id), certificates.get(enrolled.edge.id)),
          signingKey =
            SigningKeyResponse(keyId = enrolled.signingKey.keyId, privateKey = Base64Url.encode(enrolled.signingKey.privateKey.getEncoded)),
          token = enrolled.token.token,
          expiresAt = enrolled.token.expiresAt,
          bundle = enrolled.bundle,
        )
      yield Response.json(response.toJson).status(Status.Created)
    }

  /** Removes an edge of the partner. Not the one asking: an edge that deleted itself would be refused its next call, which
    * is what deleting means, but the console behind it would be left with no answer. */
  val deleteEdgeEndpoint =
    Method.DELETE / "edge" / "partner" / "edges" -> managed("partner-edge", Some(Audit.delete), "/edge/partner/edges") { (caller, _, request) =>
      for
        id <- request.url.queryZIO[EdgeId]("id")
        _ <- Audit.target(id)
        _ <- ZIO.fail(PartnerError.Conflict("an edge cannot delete itself")).when(id == caller.edge.id)
        _ <- ownEdge(caller.partner, id)
        _ <- ZIO.serviceWithZIO[EdgeService](_.deleteEdge(id))
      yield Response.status(Status.NoContent)
    }

  val createEnrollmentTokenEndpoint =
    Method.POST / "edge" / "partner" / "edges" / "enrollment-tokens" ->
      managed("partner-edge", Some(Audit.action("enrollment-tokens")), "/edge/partner/edges/enrollment-tokens") { (caller, _, request) =>
        for
          body <- request.bodyAs[EdgeIdRequest]
          _ <- Audit.target(body.id)
          token <- ZIO.serviceWithZIO[PartnerService](_.enrollmentToken(caller.partner, body.id))
        yield Response.json(PartnerEnrollmentTokenResponse(token = token.token, expiresAt = token.expiresAt).toJson).status(Status.Created)
      }

  /** Voids the unspent tokens of an edge: a bundle that was printed and must not work. */
  val revokeEnrollmentTokenEndpoint =
    Method.DELETE / "edge" / "partner" / "edges" / "enrollment-tokens" ->
      managed("partner-edge", Some(Audit.action("enrollment-tokens")), "/edge/partner/edges/enrollment-tokens") { (caller, _, request) =>
        for
          body <- request.bodyAs[EdgeIdRequest]
          _ <- Audit.target(body.id)
          _ <- ownEdge(caller.partner, body.id)
          _ <- ZIO.serviceWithZIO[EdgeEnrollmentTokenRepository](_.revoke(body.id))
        yield Response.status(Status.NoContent)
      }

  /** The partner's own name and logo: a `profile_change` request for the operator. */
  val profileEndpoint =
    Method.PUT / "edge" / "partner" / "profile" -> managed("partner", Some(Audit.update), "/edge/partner/profile") { (caller, principal, request) =>
      for
        body <- request.bodyAs[PartnerProfileRequest]
        _ <- Audit.target(caller.partner.id)
        _ <- Audit.changed(body)
        approval <- ZIO.serviceWithZIO[PartnerService](_.requestProfileChange(caller.partner, principal.sub, body.displayName, body.logoUri))
      yield Response.json(PartnerApprovalResponse.of(approval).toJson).status(Status.Accepted)
    }

  val getSettingsEndpoint =
    Method.GET / "edge" / "partner" / "settings" -> managed("partner", None, "/edge/partner/settings") { (caller, _, _) =>
      ZIO.succeed(Response.json(PartnerSettingsResponse(contact = caller.partner.contact, defaults = caller.partner.settings.defaults).toJson))
    }

  val putSettingsEndpoint =
    Method.PUT / "edge" / "partner" / "settings" -> managed("partner", Some(Audit.update), "/edge/partner/settings") { (caller, _, request) =>
      for
        body <- request.bodyAs[PartnerSettingsResponse]
        _ <- Audit.target(caller.partner.id)
        _ <- Audit.changed(body)
        _ <- ZIO.serviceWithZIO[PartnerService](_.setSettings(caller.partner, body.contact, PartnerSettings(body.defaults)))
      yield Response.status(Status.NoContent)
    }

  /** The events about this partner, newest first: what its staff did through its edges and what the operator decided about it. */
  val auditEndpoint =
    Method.GET / "edge" / "partner" / "audit" -> managed("partner", None, "/edge/partner/audit") { (caller, _, request) =>
      for
        cursor <- request.url.queryZIO[Option[String]]("cursor").flatMap(raw =>
          ZIO.foreach(raw)(value => ZIO.fromEither(AuditCursor.decode(value)).mapError(PartnerError.Invalid(_))),
        )
        actor <- request.url.queryZIO[Option[String]]("actor")
        kind <- request.url.queryZIO[Option[String]]("kind")
        q <- request.url.queryZIO[Option[String]]("q")
        events <- ZIO.serviceWithZIO[CentralAuditRepository](_.partnerEvents(
          partnerId = caller.partner.id,
          before = cursor.map(c => CentralAuditRepository.Key(c.at, c.id)),
          actor = actor.filter(_.nonEmpty),
          kind = kind.filter(_.nonEmpty),
          q = q.filter(_.nonEmpty),
          limit = AuditPageSize,
        ))
        next =
          Option.when(events.size == AuditPageSize)(events.last).map(last => AuditCursor.encode(AuditCursor(last.at, last.id, centralOnly = true)))
      yield Response.json(PartnerAuditResponse(events.map(forPartner), next).toJson)
    }

  val AuditPageSize = 50

  /** An event as the partner is shown it. The operator's own events about the partner (an approval, a suspension, new limits) say
    * that the operator did it, and what, but not who: the administrator's id, address and browser are the operator's. The
    * internal ids of the node and the trace are nobody else's, whoever acted. */
  def forPartner(event: AuditEvent): AuditEvent =
    val shown = event.copy(nodeId = "central", traceId = None)
    if event.actorType == CentralAudit.Actors.PartnerStaff then shown
    else
      shown.copy(
        actorType = ActorType("operator"),
        actorId = None,
        userId = None,
        clientId = None,
        sessionId = None,
        ip = None,
        userAgent = None,
        details = Json.Obj(event.details.fields.filter((name, _) => name == "partnerId" || name == "changed")),
      )

  // ── scoping ───────────────────────────────────────────────────────────────────────────────────

  private def approvedClients(partner: PartnerRecord): ZIO[OAuthClientService, Throwable, Vector[OAuthClientRecord]] =
    ZIO.serviceWithZIO[OAuthClientService](_.getPartnerClients(partner.id)).map(_.filter(_.isApproved))

  private def ownResources(partner: PartnerRecord): ZIO[ResourceService, Throwable, Vector[ResourceRecord]] =
    ZIO.serviceWithZIO[ResourceService](_.getResourcesForSync(None)).map(_.filter(_.partnerId.contains(partner.id)).sortBy(_.resourceId))

  private def ownResource(partner: PartnerRecord, id: ResourceId): ZIO[ResourceService, PartnerError | Throwable, ResourceRecord] =
    ownResources(partner).map(_.find(_.resourceId == id)).someOrFail(PartnerError.NotFound(s"resource '$id'"))

  private def ownEdge(partner: PartnerRecord, id: EdgeId): ZIO[PartnerService, PartnerError | Throwable, EdgeRecord] =
    ZIO.serviceWithZIO[PartnerService](_.edges(partner)).map(_.find(_.id == id)).someOrFail(PartnerError.NotFound(s"edge '$id'"))

  /** A resource's audience is the partner's own clients and no other's. */
  private def resourceUriAllowed(partner: PartnerRecord, resource: ResourceUri): IO[PartnerError, Unit] =
    ZIO.foreachDiscard(PartnerClientRules.resourceUriViolation(partner, resource))(reason => ZIO.fail(PartnerError.Invalid(reason)))

  private def audienceOwned(partner: PartnerRecord, audience: List[ClientId]): ZIO[OAuthClientService, PartnerError | Throwable, Unit] =
    ZIO.serviceWithZIO[OAuthClientService](_.getPartnerClients(partner.id)).flatMap: own =>
      val ids = own.map(_.id).toSet
      val foreign = audience.filterNot(ids.contains)
      ZIO.fail(PartnerError.Invalid(s"audience names clients that are not the partner's: ${foreign.mkString(", ")}")).when(foreign.nonEmpty).unit

  // ── the two kinds of route ────────────────────────────────────────────────────────────────────

  /** A route the edge reads for itself: the partner's edge, and nothing else, is the credential. */
  private def read(body: (PartnerCaller, Request) => ZIO[Env, PartnerError | Throwable, Response]): Handler[Env, Throwable, Request, Response] =
    handler { (request: Request) =>
      authorizePartnerEdge(request).flatMap(caller => body(caller, request).catchAll(failure)).catchAll(refusal)
    }

  /** A route the edge calls for its console: the edge, and the staff member's token in `Versola-Principal`, whose `sub` is the
    * actor of the audit event the route writes once it has answered (`operation` names it; a read writes none). */
  private def managed(resource: String, operation: Option[String], path: String)(
      body: (PartnerCaller, PartnerPrincipal.Principal, Request) => ZIO[Env, PartnerError | Throwable, Response],
  ): Handler[Env, Throwable, Request, Response] =
    handler { (request: Request) =>
      (for
        caller <- authorizePartnerEdge(request)
        principal <- PartnerPrincipal.verify(request.headers.get(PartnerPrincipal.Header), caller.partner.consoleClientId).orElseFail(Forbidden)
        response <- operation match
          case None => body(caller, principal, request).catchAll(failure)
          case Some(operation) => recorded(caller, principal, resource, operation, path)(body(caller, principal, request).catchAll(failure))
      yield response).catchAll(refusal)
    }

  /** The event of a change on this surface: `admin.<resource>.<operation>` by the partner's staff member, with the partner
    * and the edge in its details, written after the handler answered, whatever it answered. */
  private def recorded(caller: PartnerCaller, principal: PartnerPrincipal.Principal, resource: String, operation: String, path: String)(
      handle: ZIO[Env, Throwable, Response],
  ): ZIO[Env, Throwable, Response] =
    AuditContext.reset(AuditContext.State.empty.copy(
      tenantId = Some(caller.partner.tenantId),
      actor = Some((CentralAudit.Actors.PartnerStaff, Some(principal.sub))),
    )):
      ZIO.uninterruptibleMask: restore =>
        restore(handle).exit.flatMap: exit =>
          val status = exit match
            case zio.Exit.Success(response) => response.status
            case zio.Exit.Failure(_) => Status.InternalServerError
          val outcome =
            if status.isSuccess then AuditOutcome.success
            else if status == Status.Unauthorized || status == Status.Forbidden then AuditOutcome.denied
            else AuditOutcome.failure
          for
            state <- AuditContext.current
            reason <- zio.logging.logContext.get.map(_.get(Observability.error).map(_.code)).map(code =>
              Option.unless(status.isSuccess)(code.getOrElse(s"http_${status.code}")),
            )
            _ <- ZIO.serviceWithZIO[AuditEvents](_.recordBestEffort(AuditDraft(
              kind = CentralAudit.admin(resource, operation),
              outcome = outcome,
              reason = reason,
              actor = Some((CentralAudit.Actors.PartnerStaff, Some(principal.sub))),
              userId = None,
              target = state.target.map((_, id) => (resource, id)),
              details = PartnerDetails(
                path = path,
                status = status.code,
                partnerId = caller.partner.id,
                edgeId = caller.edge.id,
                changed = Option.when(state.changed.nonEmpty)(state.changed.toList.sorted),
              ),
            )))
            response <- ZIO.done(exit)
          yield response

  private def refusal(error: Throwable | Unauthorized.type | Forbidden.type): IO[Throwable, Response] =
    error match
      case Unauthorized => ZIO.succeed(Response.status(Status.Unauthorized))
      case Forbidden => ZIO.succeed(Response.status(Status.Forbidden))
      case error: Throwable => ZIO.fail(error)

  private def failure(error: PartnerError | Throwable): IO[Throwable, Response] =
    error match
      case PartnerError.NotFound(what) =>
        Observability.setError("partner_not_found", Some(what)).as(Response.text(s"$what does not exist").status(Status.NotFound))
      case PartnerError.Invalid(reason) => Observability.setError("partner_invalid", Some(reason)).as(Response.text(reason).status(Status.BadRequest))
      case PartnerError.Conflict(reason) => Observability.setError("partner_conflict", Some(reason)).as(Response.text(reason).status(Status.Conflict))
      case error: Throwable => ZIO.fail(error)

  /** What an event of this surface says about itself: the route, the status, the partner and the edge, and the names of the fields a
    * change set (never their values). `partnerId` is what the partner's feed is read by (`central_audit_event.partner_id`). */
  private case class PartnerDetails(
      path: String,
      status: Int,
      partnerId: String,
      edgeId: String,
      changed: Option[List[String]],
  ) extends AuditDetails derives JsonEncoder:
    override def toJson: Json.Obj = AuditDetails.render(this)

// ── what the edge reads ──────────────────────────────────────────────────────────────────────────

case class MePartner(
    id: PartnerId,
    displayName: String,
    logoUri: Option[String],
    status: PartnerStatus,
    limits: PartnerLimits,
    reviewAt: Option[Instant],
    tenantId: versola.central.configuration.tenants.TenantId,
) derives JsonCodec

/** Where the operator's services are: the issuer, auth's mutual-TLS listener (where the partner's clients authenticate) and
  * central's (where this surface is). Absent where the deployment did not configure a public name. */
case class MeOperator(name: String, issuer: Option[String], mtlsUrl: Option[String], centralMtlsUrl: Option[String]) derives JsonCodec

/** The console's client and its preset: the console signs in at `/login/<presetId>` and out at `/logout/<presetId>`. */
case class MeConsole(clientId: ClientId, presetId: ClientId) derives JsonCodec

case class MeEdge(id: EdgeId, publicUrl: Option[String]) derives JsonCodec

case class MeResponse(partner: MePartner, operator: MeOperator, console: MeConsole, edge: MeEdge) derives JsonCodec

// ── what the edge changes ────────────────────────────────────────────────────────────────────────

/** Where a client stands with the operator (#963 `PartnerClient.approval`). */
case class PartnerClientApprovalResponse(
    kind: PartnerClientService.RequestKind,
    status: PartnerClientService.RequestState,
    waitingFields: List[String],
    requestedAt: Option[Instant],
    note: Option[String],
) derives JsonCodec

object PartnerClientApprovalResponse:
  given JsonCodec[PartnerClientService.RequestKind] = JsonCodec.string.transformOrFail(
    name => PartnerClientService.RequestKind.values.find(_.toString == name).toRight(s"unknown kind '$name'"),
    _.toString,
  )
  given JsonCodec[PartnerClientService.RequestState] = JsonCodec.string.transformOrFail(
    name => PartnerClientService.RequestState.values.find(_.toString == name).toRight(s"unknown status '$name'"),
    _.toString,
  )

case class PartnerClientResponse(
    id: ClientId,
    kind: PartnerClientKind,
    clientName: Map[String, String],
    logoUri: Option[String],
    policyUri: Option[String],
    tosUri: Option[String],
    redirectUris: Set[versola.util.RedirectUri],
    scope: Set[versola.central.configuration.scopes.ScopeToken],
    authMethod: versola.central.configuration.clients.AuthMethod,
    jwks: Option[JsonWebKeySet],
    accessTokenTtl: Long,
    refreshTokenTtl: Long,
    backChannelLogoutUri: Option[String],
    consentFlow: Option[versola.central.configuration.ConsentFlowDto],
    dpopBoundAccessTokens: Boolean,
    createdAt: Instant,
    /** The edge's `/login/<id>`: the preset an approved client has, named after it. */
    loginPath: String,
    approval: PartnerClientApprovalResponse,
) derives JsonCodec

object PartnerClientResponse:
  def of(view: PartnerClientService.View): PartnerClientResponse =
    val client = view.client
    PartnerClientResponse(
      id = client.id,
      kind = if client.applicationType == versola.central.configuration.clients.ApplicationType.native then PartnerClientKind.native
      else PartnerClientKind.web,
      clientName = client.clientName,
      logoUri = client.logoUri,
      policyUri = client.policyUri,
      tosUri = client.tosUri,
      redirectUris = client.redirectUris,
      scope = client.scope,
      authMethod = client.authMethod,
      jwks = client.jwks,
      accessTokenTtl = client.accessTokenTtl.toSeconds,
      refreshTokenTtl = client.refreshTokenTtl.toSeconds,
      backChannelLogoutUri = client.backChannelLogoutUri.map(_.encode),
      consentFlow = client.consentFlow.map(versola.central.configuration.ConsentFlowDto.fromDomain),
      dpopBoundAccessTokens = client.dpopBoundAccessTokens,
      createdAt = client.createdAt,
      loginPath = s"/login/${client.id}",
      approval = PartnerClientApprovalResponse(
        kind = view.approval.kind,
        status = view.approval.status,
        waitingFields = view.approval.waitingFields,
        requestedAt = view.approval.requestedAt,
        note = view.approval.note,
      ),
    )

case class PartnerClientsResponse(clients: Vector[PartnerClientResponse]) derives JsonCodec

case class ClientIdRequest(id: ClientId) derives JsonCodec

case class ReplaceClientKeysRequest(id: ClientId, jwks: JsonWebKeySet) derives JsonCodec

/** A resource as a partner registers it: in the partner's tenant, owned by the partner, internal (the edge authenticates to
  * it with the secret). */
case class PartnerResourceRequest(
    resourceId: ResourceId,
    resource: ResourceUri,
    audience: List[ClientId],
    endpoints: Vector[CreateResourceEndpointRequest],
    audienceRateLimits: List[AudienceRateLimit],
) derives Schema, JsonCodec

case class PartnerResourcesResponse(resources: Vector[ResourceResponse]) derives Schema, JsonCodec

case class ResourceIdRequest(resourceId: ResourceId) derives JsonCodec

case class RotatedSecretResponse(secret: String) derives JsonCodec

case class PartnerEdgesResponse(edges: Vector[EdgeResponse]) derives Schema, JsonCodec

case class CreatePartnerEdgeRequest(id: EdgeId, publicUrl: String) derives JsonCodec

case class SigningKeyResponse(keyId: String, privateKey: String) derives JsonCodec

/** An edge with everything shown once: its signing key, its token and the bundle that carries both. */
case class CreatedPartnerEdgeResponse(edge: EdgeResponse, signingKey: SigningKeyResponse, token: String, expiresAt: Instant, bundle: String)
  derives JsonCodec

case class EdgeIdRequest(id: EdgeId) derives JsonCodec

case class PartnerEnrollmentTokenResponse(token: String, expiresAt: Instant) derives JsonCodec

case class PartnerProfileRequest(displayName: String, logoUri: Option[String]) derives Schema, JsonCodec

case class PartnerSettingsResponse(contact: PartnerContact, defaults: PartnerDefaults) derives Schema, JsonCodec

case class PartnerAuditResponse(events: List[AuditEvent], next: Option[String]) derives JsonCodec
