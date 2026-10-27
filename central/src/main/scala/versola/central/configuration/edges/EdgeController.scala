package versola.central.configuration.edges

import versola.central.configuration.clients.ClientId
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.Base64Url
import versola.util.http.{AccessLogPolicy, Controller, Observability, OverloadPolicy, RateLimitPolicies, Unauthorized}
import zio.http.*
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec, JsonEncoder}
import zio.schema.*
import zio.{Duration, UIO, ZIO}

import java.time.Instant

object EdgeController extends Controller:
  type Env = Tracing & EdgeService & EdgeGroupService & EdgeIdentityService & ResourceService & CentralConfig & TenantRepository

  def routes: Routes[Env, Throwable] = Routes(
    getAllEdgesEndpoint,
    registerEdgeEndpoint,
    rotateEdgeKeyEndpoint,
    deleteOldEdgeKeyEndpoint,
    deleteEdgeEndpoint,
    edgesRegistryEndpoint,
    setEdgeDpopEndpoint,
    getEdgePoliciesEndpoint,
    patchEdgePoliciesEndpoint,
    recommendedRateLimitsEndpoint,
    createEdgeGroupEndpoint,
    getEdgeGroupsEndpoint,
    createEnrollmentTokenEndpoint,
    extendEdgeEndpoint,
  )

  val getAllEdgesEndpoint =
    Method.GET / "configuration" / "edges" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edges <- service.getAllEdges
        chainEnds <- ZIO.serviceWithZIO[EdgeIdentityService](_.chainEnds)
        response = GetAllEdgesResponse(
          edges = edges.map(edge =>
            EdgeResponse(
              id = edge.id,
              hasOldKey = edge.oldPublicKey.isDefined,
              requireDpopNonce = edge.requireDpopNonce,
              overloadPolicy = edge.overloadPolicy,
              accessLogPolicy = edge.accessLogPolicy,
              rateLimitPolicies = edge.rateLimitPolicies,
              policyRevisions = edge.policyMeta.fold(Map.empty[String, Int])(_.view.mapValues(_.revision).toMap),
              groupId = edge.groupId,
              identityChainEndsAt = chainEnds.get(edge.id),
            ),
          ).toList,
        )
      yield Response.json(response.toJson)
    }

  val registerEdgeEndpoint =
    Method.POST / "configuration" / "edges" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        body <- request.bodyAs[RegisterEdgeRequest]
        groupId = body.groupId.getOrElse(EdgeGroup.DefaultId)
        group <- ZIO.serviceWithZIO[EdgeGroupService](_.find(groupId))
        response <- group match
          case None => ZIO.succeed(Response.text(s"edge group '$groupId' does not exist").status(Status.UnprocessableEntity))
          case Some(_) =>
            service.registerEdge(body.id, groupId).map: keyPair =>
              val privateKeyEncoded = Base64Url.encode(keyPair.privateKey.getEncoded)
              Response.json(ServiceKeyResponse(keyId = keyPair.keyId, privateKey = privateKeyEncoded).toJson).status(Status.Created)
      yield response
    }

  /** Creates a group of edges. The kind is the operator's decision and is not changed afterwards; an existing id is
    * answered `409` and left as it was. */
  val createEdgeGroupEndpoint =
    Method.POST / "configuration" / "edge-groups" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[EdgeGroupRecord]
        created <- ZIO.serviceWithZIO[EdgeGroupService](_.create(body))
      yield if created then Response.json(body.toJson).status(Status.Created) else Response.status(Status.Conflict)
    }

  val getEdgeGroupsEndpoint =
    Method.GET / "configuration" / "edge-groups" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        groups <- ZIO.serviceWithZIO[EdgeGroupService](_.getAll)
      yield Response.json(GetEdgeGroupsResponse(groups).toJson)
    }

  /** Starts the certificate chain of the edge over, for an operator who turned `edge-identity.auto-extend` off: the edge's
    * next renewals are not refused for age. A no-op in effect where central extends chains itself. */
  val extendEdgeEndpoint =
    Method.POST / "configuration" / "edges" / "extend" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        response <- ZIO.serviceWithZIO[EdgeIdentityService](_.extend(edgeId)).as(Response.status(Status.NoContent))
          .catchSome:
            case error: EdgeIdentityService.Refused => ZIO.succeed(Response.text(error.getMessage).status(Status.NotFound))
      yield response
    }

  /** A one-time token for the named edge to trade for its first certificate, shown once. */
  val createEnrollmentTokenEndpoint =
    Method.POST / "configuration" / "edges" / "enrollment-tokens" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        hours <- ZIO.succeed(request.url.queryParams.getAll("validityHours").headOption.flatMap(_.toLongOption))
        validity = hours.fold(EdgeIdentityService.TokenValidity)(h => Duration.fromSeconds(h * 3600L))
        response <- ZIO.serviceWithZIO[EdgeIdentityService](_.issueEnrollmentToken(edgeId, validity)).map: issued =>
          Response.json(EnrollmentTokenResponse(token = issued.token, expiresAt = issued.expiresAt).toJson).status(Status.Created)
        .catchSome:
          case error: EdgeIdentityService.Refused => ZIO.succeed(Response.text(error.getMessage).status(Status.NotFound))
      yield response
    }

  val rotateEdgeKeyEndpoint =
    Method.POST / "configuration" / "edges" / "rotate-key" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        keyPair <- service.rotateEdgeKey(edgeId)
        response = ServiceKeyResponse(
          keyId = keyPair.keyId,
          privateKey = Base64Url.encode(keyPair.privateKey.getEncoded),
        )
      yield Response.json(response.toJson)
    }

  val deleteOldEdgeKeyEndpoint =
    Method.DELETE / "configuration" / "edges" / "old-key" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        _ <- service.deleteOldEdgeKey(edgeId)
      yield Response.status(Status.NoContent)
    }

  val deleteEdgeEndpoint =
    Method.DELETE / "configuration" / "edges" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        _ <- service.deleteEdge(edgeId)
      yield Response.status(Status.NoContent)
    }

  val setEdgeDpopEndpoint =
    Method.PUT / "configuration" / "edges" / "dpop" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        body <- request.bodyAs[SetEdgeDpopRequest]
        edge <- service.find(edgeId)
        response <-
          if edge.isEmpty then ZIO.succeed(Response.status(Status.NotFound))
          else service.setRequireDpopNonce(edgeId, body.requireDpopNonce).as(Response.status(Status.NoContent))
      yield response
    }

  /** The policy sections of one edge as stored now (not the cache's last copy): `overload`, `accessLog` and `rateLimits`, each absent
    * when none is set, with the `revisions` and `updatedAt` of the sections that have ever changed.
    */
  val getEdgePoliciesEndpoint =
    Method.GET / "configuration" / "edges" / "policies" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        edge <- service.findFresh(edgeId)
      yield edge.fold(Response.status(Status.NotFound))(record => Response.json(EdgePoliciesResponse.of(record).toJson))
    }

  /** Changes the policy sections of one edge ([[EdgePoliciesPatch]]): the sections named in the body are changed, the others are
    * left as they are; `null` removes a section and an object replaces it as a whole. `expectedRevisions` makes a section's change
    * conditional on its revision (`409` with the current state if it has moved on). `dryRun=true` checks the body with the rules the
    * edge applies and answers what the state would be, writing nothing. `400` names the section and the first thing wrong; the edge
    * would not apply an invalid policy either, and a console that reported success for it would leave an operator believing a limit
    * was in force. The answer is the new state, so a client has the new revisions without another call.
    */
  val patchEdgePoliciesEndpoint =
    Method.PATCH / "configuration" / "edges" / "policies" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        dryRun <- request.url.queryZIO[Option[String]]("dryRun").map(_.contains("true"))
        body <- request.bodyAs[Json.Obj]
        response <- EdgePoliciesPatch.parse(body) match
          case Left(error) => policyError(error)
          case Right(patch) =>
            service.patchPolicies(edgeId, patch, dryRun).flatMap:
              case None => ZIO.succeed(Response.status(Status.NotFound))
              case Some(Left(error)) => policyError(error)
              case Some(Right(record)) => ZIO.succeed(Response.json(EdgePoliciesResponse.of(record).toJson))
      yield response
    }

  /** No body, whatever the reason: a body would be a contract (`400` is not an answer a client of the console should ever get, since the console
    * validates what it sends, and a `409` is answered by reading the state again). The reason is in central's log for the operator.
    */
  private def policyError(error: PolicyPatchError): UIO[Response] =
    val status = error match
      case _: PolicyPatchError.Conflict => Status.Conflict
      case _ => Status.BadRequest
    Observability.setError(if status == Status.Conflict then "policy_conflict" else "invalid_policy", Some(error.message)).as(Response.status(status))

  /** The starting set of rate limit policies (all in `shadow`) that the console offers with one click and an operator can send
    * to [[patchEdgePoliciesEndpoint]]; the same on every edge, so it is not read from one.
    */
  val recommendedRateLimitsEndpoint =
    Method.GET / "configuration" / "edges" / "rate-limits" / "recommended" -> handler { (request: Request) =>
      authorizeBasic(request).as(Response.json(RateLimitPolicies.recommended.toJson))
    }

  /** Which edges exist, and the tenants each is assigned to serve, for auth to decide whether a
    * certificate that names an edge (`urn:versola:edge:<id>`) belongs to one central still
    * knows: the certificate's own validity cannot be shortened from here, so deleting an edge
    * has to reach auth some other way (see `versola.oauth.mtls.EdgeCertificateTrust`). No
    * keys: auth recognises an edge by its certificate, not by a key it holds.
    *
    * Restricted to auth, which `authorizeInternal` reports as an absent edge id: an edge has no
    * use for its peers' identities.
    */
  val edgesRegistryEndpoint =
    Method.GET / "configuration" / "edges" / "registry" -> handler { (request: Request) =>
      for
        callerEdgeId <- authorizeInternal(request)
        _ <- ZIO.fail(Unauthorized).when(callerEdgeId.isDefined)
        service <- ZIO.service[EdgeService]
        edges <- service.getAllEdges
        tenants <- ZIO.serviceWithZIO[TenantRepository](_.getAll)
        response = GetEdgesRegistryResponse(
          edges = edges.map(edge =>
            EdgeRegistryEntry(
              id = edge.id,
              tenantIds = tenants.filter(_.edgeId.contains(edge.id)).map(_.id).toList,
            ),
          ).toList,
        )
      yield Response.json(response.toJson)
    }

case class RegisterEdgeRequest(
    id: EdgeId,
    /** The group the edge joins and never leaves; absent means `default`. */
    groupId: Option[EdgeGroupId] = None,
) derives Schema, JsonCodec

case class GetEdgeGroupsResponse(
    groups: Vector[EdgeGroupRecord],
) derives Schema, JsonCodec

case class EnrollmentTokenResponse(
    token: String,
    expiresAt: Instant,
) derives Schema, JsonCodec

case class EdgeResponse(
    id: EdgeId,
    hasOldKey: Boolean,
    requireDpopNonce: Boolean,
    overloadPolicy: Option[OverloadPolicy] = None,
    accessLogPolicy: Option[AccessLogPolicy] = None,
    rateLimitPolicies: Option[RateLimitPolicies] = None,
    /** The revision of each policy section that has ever changed, for the compare-and-set of `PATCH /configuration/edges/policies`. */
    policyRevisions: Map[String, Int] = Map.empty,
    groupId: EdgeGroupId = EdgeGroup.DefaultId,
    /** When the certificate chain of this edge ends, if it does: only where `edge-identity.auto-extend` is off. */
    identityChainEndsAt: Option[Instant] = None,
) derives Schema, JsonCodec

case class SetEdgeDpopRequest(
    requireDpopNonce: Boolean,
) derives Schema, JsonCodec

case class EdgeDpopSyncResponse(
    requireDpopNonce: Boolean,
) derives Schema, JsonCodec

case class EdgeOverloadSyncResponse(
    overloadPolicy: Option[OverloadPolicy],
) derives Schema, JsonCodec

case class EdgeAccessLogSyncResponse(
    accessLogPolicy: Option[AccessLogPolicy],
) derives Schema, JsonCodec

case class EdgeRateLimitSyncResponse(
    rateLimitPolicies: Option[RateLimitPolicies],
) derives Schema, JsonCodec

/** The policy sections of an edge: absent when none is set, with how often and when each section last changed. */
case class EdgePoliciesResponse(
    edgeId: EdgeId,
    overload: Option[OverloadPolicy] = None,
    accessLog: Option[AccessLogPolicy] = None,
    rateLimits: Option[RateLimitPolicies] = None,
    revisions: Map[String, Int] = Map.empty,
    updatedAt: Map[String, Instant] = Map.empty,
) derives Schema, JsonCodec

object EdgePoliciesResponse:
  def of(record: EdgeRecord): EdgePoliciesResponse =
    val meta = record.policyMeta.getOrElse(Map.empty)
    EdgePoliciesResponse(
      edgeId = record.id,
      overload = record.overloadPolicy,
      accessLog = record.accessLogPolicy,
      rateLimits = record.rateLimitPolicies,
      revisions = meta.view.mapValues(_.revision).toMap,
      updatedAt = meta.view.mapValues(_.updatedAt).toMap,
    )

case class GetAllEdgesResponse(
    edges: List[EdgeResponse],
) derives Schema, JsonCodec

case class EdgeRegistryEntry(
    id: EdgeId,
    tenantIds: List[TenantId],
) derives JsonCodec

case class GetEdgesRegistryResponse(
    edges: List[EdgeRegistryEntry],
) derives JsonCodec

case class ServiceKeyResponse(
    keyId: String,
    privateKey: String,
) derives Schema, JsonEncoder
