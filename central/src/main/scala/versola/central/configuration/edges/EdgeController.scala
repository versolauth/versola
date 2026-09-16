package versola.central.configuration.edges

import versola.central.configuration.clients.ClientId
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.Base64Url
import versola.util.http.{Controller, Unauthorized}
import zio.ZIO
import zio.http.*
import zio.json.ast.Json
import zio.json.{EncoderOps, JsonCodec, JsonEncoder}
import zio.schema.*

object EdgeController extends Controller:
  type Env = Tracing & EdgeService & ResourceService & CentralConfig & TenantRepository

  def routes: Routes[Env, Throwable] = Routes(
    getAllEdgesEndpoint,
    registerEdgeEndpoint,
    rotateEdgeKeyEndpoint,
    deleteOldEdgeKeyEndpoint,
    deleteEdgeEndpoint,
    edgesRegistryEndpoint,
    setEdgeDpopEndpoint,
    edgeDpopSyncEndpoint,
  )

  val getAllEdgesEndpoint =
    Method.GET / "configuration" / "edges" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edges <- service.getAllEdges
        response = GetAllEdgesResponse(
          edges = edges.map(edge =>
            EdgeResponse(
              id = edge.id,
              hasOldKey = edge.oldPublicKey.isDefined,
              requireDpopNonce = edge.requireDpopNonce,
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
        keyPair <- service.registerEdge(body.id)
        privateKeyEncoded = Base64Url.encode(keyPair.privateKey.getEncoded)
        response = ServiceKeyResponse(keyId = keyPair.keyId, privateKey = privateKeyEncoded)
      yield Response.json(response.toJson).status(Status.Created)
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

  /** What the named edge is to do about RFC 9449 §9 on the calls it proxies, for the edge
    * itself -- the resource-server half of the same decision a tenant's `require_dpop_nonce`
    * makes for `auth`'s endpoints.
    *
    * Restricted to edges, and an edge is served only its own row: an edge has no use for a
    * peer's policy, and `authorizeInternal` has already proven which edge is asking by the key
    * it signed the call with, so the id is not taken from the request.
    */
  val edgeDpopSyncEndpoint =
    Method.GET / "configuration" / "edges" / "dpop" / "sync" -> handler { (request: Request) =>
      for
        callerEdgeId <- authorizeInternal(request)
        edgeId <- ZIO.fromOption(callerEdgeId).orElseFail(Unauthorized)
        service <- ZIO.service[EdgeService]
        edge <- service.find(edgeId).someOrFail(Unauthorized)
        response = EdgeDpopSyncResponse(requireDpopNonce = edge.requireDpopNonce)
      yield Response.json(response.toJson)
    }

  /** The registered signing keys of every edge, for auth to authenticate the edges that call
    * it directly (see `versola.util.EdgeAssertion`). Public keys only -- the same JWKs
    * `authorizeInternal` verifies an edge's own sync calls against.
    *
    * Restricted to auth, which `authorizeInternal` reports as an absent edge id: an edge has
    * no use for its peers' keys, and handing them over would let a single compromised edge
    * learn the identities it would have to forge.
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
              publicKey = edge.publicKey,
              oldPublicKey = edge.oldPublicKey,
              // What lets auth refuse an assertion from an edge that is real and correctly
              // signed but not the one this token's tenant is behind -- see
              // `versola.util.EdgeAssertion`.
              tenantIds = tenants.filter(_.edgeId.contains(edge.id)).map(_.id).toList,
            ),
          ).toList,
        )
      yield Response.json(response.toJson)
    }

case class RegisterEdgeRequest(
    id: EdgeId,
) derives Schema, JsonCodec

case class EdgeResponse(
    id: EdgeId,
    hasOldKey: Boolean,
    requireDpopNonce: Boolean,
) derives Schema, JsonCodec

case class SetEdgeDpopRequest(
    requireDpopNonce: Boolean,
) derives Schema, JsonCodec

case class EdgeDpopSyncResponse(
    requireDpopNonce: Boolean,
) derives Schema, JsonCodec

case class GetAllEdgesResponse(
    edges: List[EdgeResponse],
) derives Schema, JsonCodec

/** `oldPublicKey` is present only during a rotation window, and is sent so auth keeps
  * accepting assertions an edge signed with the key it is rotating out. */
case class EdgeRegistryEntry(
    id: EdgeId,
    publicKey: Json.Obj,
    oldPublicKey: Option[Json.Obj],
    tenantIds: List[TenantId],
) derives JsonCodec

case class GetEdgesRegistryResponse(
    edges: List[EdgeRegistryEntry],
) derives JsonCodec

case class ServiceKeyResponse(
    keyId: String,
    privateKey: String,
) derives Schema, JsonEncoder
