package versola.central.configuration.edges

import versola.central.configuration.clients.attestation.ClientAttestationService
import versola.central.configuration.clients.certificates.ClientCertificateService
import versola.central.configuration.clients.{AuthorizationPreset, AuthorizationPresetService, ClientController, OAuthClientService}
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.central.configuration.partners.PartnerRegistry
import versola.central.configuration.permissions.PermissionService
import versola.central.configuration.resources.{ResourceRecord, ResourceService}
import versola.central.configuration.roles.RoleService
import versola.central.configuration.{
  AuthorizationPresetSyncResponse,
  GetAuthorizationPresetsSyncResponse,
  GetPermissionsSyncResponse,
  GetResourcesSyncResponse,
  GetRolesSyncResponse,
  PermissionSyncResponse,
  ResourceEndpointSyncResponse,
  ResourceSyncResponse,
  RoleSyncResponse,
  SignEdgeCertificateRequest,
  SignEdgeCertificateResponse,
}
import versola.central.{CentralConfig, authorizeEdge, authorizeRenewal}
import versola.util.http.{Controller, Forbidden, Unauthorized}
import versola.util.{Base64Url, Dpop, SecurityService}
import zio.ZIO
import zio.http.*
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec}
import zio.schema.*

import java.security.cert.X509Certificate

/** The surface central serves to edges and to nobody else (#554): `GET /edge/sync/<resource>`
  * for what an edge reads, and the certificate signing an edge enrols through. Every route
  * authenticates the caller as a registered edge with [[authorizeEdge]] and answers for that edge
  * only -- the tenants bound to it, its own DPoP policy -- so an edge serving one tenant learns
  * nothing of another's. Auth and the admin console read under `/configuration`, where an edge
  * token is refused ([[versola.central.refuseEdgeCallers]]).
  */
object EdgeSyncController extends Controller:
  type Env = Tracing & CentralConfig & EdgeService & EdgeIdentityService & SecurityService & OAuthClientService & ClientCertificateService &
    ClientAttestationService & AuthorizationPresetService & ResourceService & RoleService & PermissionService & JwksService & ServerMetadataService & PartnerRegistry

  def routes: Routes[Env, Throwable] = Routes(
    clientsEndpoint,
    resourcesEndpoint,
    rolesEndpoint,
    permissionsEndpoint,
    jwksEndpoint,
    dpopEndpoint,
    overloadEndpoint,
    accessLogEndpoint,
    rateLimitEndpoint,
    presetsEndpoint,
    serverMetadataEndpoint,
    signEdgeCertificateEndpoint,
    renewIdentityEndpoint,
  )

  val clientsEndpoint =
    Method.GET / "edge" / "sync" / "clients" -> handler { (request: Request) =>
      for
        edgeId <- authorizeEdge(request)
        response <- ClientController.clientsSyncResponse(Some(edgeId))
      yield Response.json(response.toJson)
    }

  val resourcesEndpoint =
    Method.GET / "edge" / "sync" / "resources" -> handler { (request: Request) =>
      for
        edge <- callerEdge(request)
        service <- ZIO.service[ResourceService]
        resources <- service.getResourcesForSync(Some(edge.id))
        encryptedResources <- ZIO.foreach(resources)(toResourceSyncResponse(_, edge))
      yield Response.json(GetResourcesSyncResponse(encryptedResources).toJson)
    }

  val rolesEndpoint =
    Method.GET / "edge" / "sync" / "roles" -> handler { (request: Request) =>
      for
        edgeId <- authorizeEdge(request)
        service <- ZIO.service[RoleService]
        roles <- service.getRolesForSync(Some(edgeId))
        response = GetRolesSyncResponse(roles.map { role =>
          RoleSyncResponse(
            tenantId = role.tenantId,
            id = role.id,
            permissions = role.permissions,
            active = role.active,
          )
        })
      yield Response.json(response.toJson)
    }

  val permissionsEndpoint =
    Method.GET / "edge" / "sync" / "permissions" -> handler { (request: Request) =>
      for
        edgeId <- authorizeEdge(request)
        service <- ZIO.service[PermissionService]
        permissions <- service.getPermissionsForSync(Some(edgeId))
        response = GetPermissionsSyncResponse(permissions.map { p =>
          PermissionSyncResponse(
            tenantId = p.tenantId,
            id = p.id,
            endpointIds = p.endpointIds,
          )
        })
      yield Response.json(response.toJson)
    }

  /** Public halves only: edge verifies, and must never be sent key material it cannot need. The
    * private halves auth signs with are on `/configuration/jwks/signing-keys/sync`, where an edge
    * is refused. */
  val jwksEndpoint =
    Method.GET / "edge" / "sync" / "jwks" -> handler { (request: Request) =>
      for
        _ <- authorizeEdge(request)
        service <- ZIO.service[JwksService]
        jwks <- service.getRaw
      yield Response.json(jwks.toJson)
    }

  /** What the calling edge is to do about RFC 9449 §9 on the calls it proxies -- the
    * resource-server half of the same decision a tenant's `require_dpop_nonce` makes for `auth`'s
    * endpoints. An edge is served only its own row: it has no use for a peer's policy, and
    * [[authorizeEdge]] has already proven which edge is asking by the key it signed the call
    * with, so the id is not taken from the request.
    */
  val dpopEndpoint =
    Method.GET / "edge" / "sync" / "dpop" -> handler { (request: Request) =>
      for
        edge <- callerEdge(request)
        response = EdgeDpopSyncResponse(requireDpopNonce = edge.requireDpopNonce)
      yield Response.json(response.toJson)
    }

  /** What the calling edge does when asked for more than it can do, as an operator set it for
    * that edge (`edges.overload_policy`); `null` when none is set. Served like [[dpopEndpoint]]:
    * the edge's own row only, the edge named by the certificate and the token, never by the
    * request. */
  val overloadEndpoint =
    Method.GET / "edge" / "sync" / "overload" -> handler { (request: Request) =>
      for
        edge <- callerEdge(request)
        response = EdgeOverloadSyncResponse(overloadPolicy = edge.overloadPolicy)
      yield Response.json(response.toJson)
    }

  /** The access-log policy an operator set for the calling edge (`edges.access_log_policy`); `null` when none is set. Served
    * like [[overloadEndpoint]]: the edge's own row only. */
  val accessLogEndpoint =
    Method.GET / "edge" / "sync" / "access-log" -> handler { (request: Request) =>
      for
        edge <- callerEdge(request)
        response = EdgeAccessLogSyncResponse(accessLogPolicy = edge.accessLogPolicy)
      yield Response.json(response.toJson)
    }

  /** The rate limit policies an operator set for the calling edge (`edges.rate_limit_policies`); `null` when none are set.
    * Served like [[overloadEndpoint]]: the edge's own row only. */
  val rateLimitEndpoint =
    Method.GET / "edge" / "sync" / "rate-limits" -> handler { (request: Request) =>
      for
        edge <- callerEdge(request)
        response = EdgeRateLimitSyncResponse(rateLimitPolicies = edge.rateLimitPolicies)
      yield Response.json(response.toJson)
    }

  val presetsEndpoint =
    Method.GET / "edge" / "sync" / "auth-request-presets" -> handler { (request: Request) =>
      for
        edgeId <- authorizeEdge(request)
        service <- ZIO.service[AuthorizationPresetService]
        presets <- service.getPresetsForSync(Some(edgeId))
      yield Response.json(presetsSyncResponse(presets).toJson)
    }

  /** Presets as an edge is served them; shared with the partner surface. */
  private[central] def presetsSyncResponse(presets: Vector[AuthorizationPreset]): GetAuthorizationPresetsSyncResponse =
    GetAuthorizationPresetsSyncResponse(presets.map { preset =>
      AuthorizationPresetSyncResponse(
        id = preset.id,
        clientId = preset.clientId,
        description = preset.description,
        redirectUri = preset.redirectUri,
        postLoginRedirectUri = preset.postLoginRedirectUri,
        postLogoutRedirectUri = preset.postLogoutRedirectUri,
        scope = preset.scope,
        responseType = preset.responseType,
        uiLocales = preset.uiLocales,
        customParameters = preset.customParameters,
        cookieDomain = preset.cookieDomain,
        cookiePath = preset.cookiePath,
      )
    })

  /** The one field of the authorization server metadata an edge acts on: the DPoP proof
    * algorithms (RFC 9449 §5.1), which it checks proxied calls' proofs against (edge's
    * `DpopAlgorithmsSyncClient`). The rest of the document is auth's to serve and an edge has
    * no use for it, so it is not sent. Absent when the operator set none, which edge reads as
    * its default.
    */
  val serverMetadataEndpoint =
    Method.GET / "edge" / "sync" / "server-metadata" -> handler { (request: Request) =>
      for
        _ <- authorizeEdge(request)
        reduced <- reducedServerMetadata
      yield Response.json(reduced.toJson)
    }

  /** The DPoP algorithms of the metadata document, and nothing else; shared with the partner surface. */
  private[central] val reducedServerMetadata: ZIO[ServerMetadataService, Throwable, Json.Obj] =
    for
      service <- ZIO.service[ServerMetadataService]
      metadata <- service.getMetadata
      algorithms = metadata.flatMap(_.get(Dpop.Algorithm.MetadataField))
    yield Json.Obj(algorithms.map(Dpop.Algorithm.MetadataField -> _).toSeq*)

  /** #463: an edge's request for a certificate for a client it generated the key of. Authenticated
    * as the edge itself (the signed token it syncs with), never as an admin: it is not on the admin
    * API's permission catalog, and central answers it only for a registered edge. */
  val signEdgeCertificateEndpoint =
    Method.POST / "edge" / "clients" / "edge-certificate" / "sign" -> handler { (request: Request) =>
      (for
        edgeId <- authorizeEdge(request)
        service <- ZIO.service[ClientCertificateService]
        body <- request.bodyAs[SignEdgeCertificateRequest]
        chain <- service.sign(edgeId, body.clientId, body.csr)
      yield Response.json(SignEdgeCertificateResponse(chain).toJson))
        .catchAll {
          case error: ClientCertificateService.SigningRefused =>
            ZIO.succeed(Response.text(error.getMessage).status(Status.UnprocessableEntity))
          case Unauthorized => ZIO.succeed(Response.status(Status.Unauthorized))
          case error: Throwable => ZIO.fail(error)
        }
    }

  /** A new certificate for the calling edge itself, for the one it has: asked for over the mutual-TLS listener, so the
    * handshake and the token have already said which edge this is. The request must name exactly that edge and its
    * group, and carries a new key every time. The one route an edge of either kind reaches ([[authorizeRenewal]]): a
    * partner's edge keeps renewing for as long as it is registered and its partner is not suspended (403 then). */
  val renewIdentityEndpoint =
    Method.POST / "edge" / "certificate" / "renew" -> handler { (request: Request) =>
      (for
        edge <- authorizeRenewal(request)
        body <- request.bodyAs[RenewEdgeCertificateRequest]
        identity <- ZIO.serviceWithZIO[EdgeIdentityService](_.renew(
          edge.id,
          body.csr,
          request.remoteCertificate.collect { case certificate: X509Certificate => certificate.getSerialNumber.toString(16) },
          body.signingKey,
        ))
      yield Response.json(EdgeIdentityResponse(identity.chain).toJson))
        .catchAll {
          case error: EdgeIdentityService.Refused =>
            ZIO.succeed(Response.text(error.getMessage).status(Status.UnprocessableEntity))
          case Unauthorized => ZIO.succeed(Response.status(Status.Unauthorized))
          case Forbidden => ZIO.succeed(Response.status(Status.Forbidden))
          case error: Throwable => ZIO.fail(error)
        }
    }

  /** The calling edge's record, for the routes that encrypt to its key or answer with its own
    * row. `authorizeEdge` has verified the token against this same record. */
  private def callerEdge(request: Request): ZIO[CentralConfig & EdgeService, Unauthorized.type, EdgeRecord] =
    for
      edgeId <- authorizeEdge(request)
      edge <- ZIO.serviceWithZIO[EdgeService](_.find(edgeId)).someOrFail(Unauthorized)
    yield edge

  /** A resource as an edge is served it, the secret encrypted to the edge's key; shared with the partner surface. */
  private[central] def toResourceSyncResponse(record: ResourceRecord, edge: EdgeRecord) =
    // Keep edge on the old secret until the previous secret is explicitly removed.
    val effectiveSecret = record.previousSecret.orElse(record.secret)
    ZIO.foreach(effectiveSecret)(secret =>
      ZIO.serviceWithZIO[SecurityService](_.encryptRsa(secret, edge.activeRsaPublicKey)).map(Base64Url.encode),
    ).map { encryptedSecret =>
      ResourceSyncResponse(
        resourceId = record.resourceId,
        tenantId = record.tenantId,
        resource = record.resource,
        endpoints = record.endpoints.map { endpoint =>
          ResourceEndpointSyncResponse(
            id = endpoint.id,
            method = endpoint.method,
            path = endpoint.path,
            fetchUserInfo = endpoint.fetchUserInfo,
            allow = endpoint.allowExpression,
            inject = endpoint.inject,
            stepUpCondition = endpoint.stepUpCondition,
            stepUpAcr = endpoint.stepUpAcr,
            maxAge = endpoint.maxAge,
            rateLimit = endpoint.rateLimit,
          )
        },
        secret = encryptedSecret,
        audienceRateLimits = record.audienceRateLimits,
      )
    }

/** `signingKey` is a new public JWK for the edge to sign with: central replaces the edge's key with it once the certificate is
  * signed, and the edge switches after it has installed the certificate. */
case class RenewEdgeCertificateRequest(
    csr: String,
    signingKey: Option[Json.Obj] = None,
) derives JsonCodec, Schema
