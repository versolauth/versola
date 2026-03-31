package versola.central.configuration.edges

import versola.central.configuration.clients.ClientId
import versola.central.configuration.tenants.TenantId
import versola.util.Base64Url
import versola.util.http.Controller
import zio.ZIO
import zio.http.*
import zio.json.{EncoderOps, JsonCodec, JsonEncoder}
import zio.schema.*

object EdgeController extends Controller:
  type Env = Tracing & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllEdgesEndpoint,
    registerEdgeEndpoint,
    rotateEdgeKeyEndpoint,
    deleteOldEdgeKeyEndpoint,
    deleteEdgeEndpoint,
  )

  val getAllEdgesEndpoint =
    Method.GET / "v1" / "configuration" / "edges" -> handler { (_: Request) =>
      for
        service <- ZIO.service[EdgeService]
        edges <- service.getAllEdges
        response = GetAllEdgesResponse(
          edges = edges.map(edge =>
            EdgeResponse(
              id = edge.id,
              hasOldKey = edge.oldPublicKey.isDefined,
            ),
          ).toList,
        )
      yield Response.json(response.toJson)
    }

  val registerEdgeEndpoint =
    Method.POST / "v1" / "configuration" / "edges" -> handler { (request: Request) =>
      for
        service <- ZIO.service[EdgeService]
        body <- request.body.asJson[RegisterEdgeRequest]
        keyPair <- service.registerEdge(body.id)
        privateKeyEncoded = Base64Url.encode(keyPair.privateKey.getEncoded)
        response = ServiceKeyResponse(keyId = keyPair.keyId, privateKey = privateKeyEncoded)
      yield Response.json(response.toJson).status(Status.Created)
    }

  val rotateEdgeKeyEndpoint =
    Method.POST / "v1" / "configuration" / "edges" / "rotate-key" -> handler { (request: Request) =>
      for
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
    Method.DELETE / "v1" / "configuration" / "edges" / "old-key" -> handler { (request: Request) =>
      for
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        _ <- service.deleteOldEdgeKey(edgeId)
      yield Response.status(Status.NoContent)
    }

  val deleteEdgeEndpoint =
    Method.DELETE / "v1" / "configuration" / "edges" -> handler { (request: Request) =>
      for
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        _ <- service.deleteEdge(edgeId)
      yield Response.status(Status.NoContent)
    }

case class RegisterEdgeRequest(
    id: EdgeId,
) derives Schema, JsonCodec

case class EdgeResponse(
    id: EdgeId,
    hasOldKey: Boolean,
) derives Schema, JsonCodec

case class GetAllEdgesResponse(
    edges: List[EdgeResponse],
) derives Schema, JsonCodec

case class ServiceKeyResponse(
    keyId: String,
    privateKey: String,
) derives Schema, JsonEncoder
