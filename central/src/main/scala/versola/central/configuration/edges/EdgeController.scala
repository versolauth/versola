package versola.central.configuration.edges

import versola.central.configuration.clients.ClientId
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.Base64Url
import versola.util.http.{Controller, Unauthorized}
import zio.ZIO
import zio.http.*
import zio.json.{EncoderOps, JsonCodec, JsonEncoder}
import zio.schema.*

object EdgeController extends Controller:
  type Env = Tracing & EdgeService & ResourceService & CentralConfig

  def routes: Routes[Env, Throwable] = Routes(
    getAllEdgesEndpoint,
    getSelfSyncEndpoint,
    registerEdgeEndpoint,
    updateEdgeEndpoint,
    rotateEdgeKeyEndpoint,
    deleteOldEdgeKeyEndpoint,
    deleteEdgeEndpoint,
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
              revocationCacheSize = edge.revocationCacheSize,
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
        body <- request.body.asJsonFromCodec[RegisterEdgeRequest]
        keyPair <- service.registerEdge(body.id)
        privateKeyEncoded = Base64Url.encode(keyPair.privateKey.getEncoded)
        response = ServiceKeyResponse(keyId = keyPair.keyId, privateKey = privateKeyEncoded)
      yield Response.json(response.toJson).status(Status.Created)
    }

  /** The settings an edge reads about itself. `authorizeInternal` returns the caller's own
    * id, so an edge can only ever ask for its own — there is nothing to authorize beyond
    * being an edge at all. The auth service also authenticates this way but has no edge id,
    * and no settings here to read.
    */
  val getSelfSyncEndpoint =
    Method.GET / "configuration" / "edges" / "self" / "sync" -> handler { (request: Request) =>
      for
        edgeId <- authorizeInternal(request).someOrFail(Unauthorized)
        service <- ZIO.service[EdgeService]
        edge <- service.find(edgeId).someOrFail(Unauthorized)
        response = EdgeSettingsSyncResponse(revocationCacheSize = edge.revocationCacheSize)
      yield Response.json(response.toJson)
    }

  val updateEdgeEndpoint =
    Method.PATCH / "configuration" / "edges" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        body <- request.body.asJsonFromCodec[UpdateEdgeRequest]
        _ <- service.updateRevocationCacheSize(body.id, body.revocationCacheSize)
      yield Response.status(Status.NoContent)
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

case class RegisterEdgeRequest(
    id: EdgeId,
) derives Schema, JsonCodec

case class UpdateEdgeRequest(
    id: EdgeId,
    revocationCacheSize: Int,
) derives Schema, JsonCodec

case class EdgeResponse(
    id: EdgeId,
    hasOldKey: Boolean,
    revocationCacheSize: Int,
) derives Schema, JsonCodec

case class EdgeSettingsSyncResponse(
    revocationCacheSize: Int,
) derives Schema, JsonCodec

case class GetAllEdgesResponse(
    edges: List[EdgeResponse],
) derives Schema, JsonCodec

case class ServiceKeyResponse(
    keyId: String,
    privateKey: String,
) derives Schema, JsonEncoder
