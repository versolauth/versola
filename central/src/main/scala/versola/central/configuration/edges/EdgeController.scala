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
    setEdgeDpopEndpoint,
    setEdgeCertificateEndpoint,
    deleteOldEdgeCertificateEndpoint,
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
              hasCertificate = edge.certificateThumbprint.isDefined,
              hasOldCertificate = edge.oldCertificateThumbprint.isDefined,
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

  /** Pins the client certificate the edge presents on central's mutual-TLS listener -- the
    * only place the edge surface is served (`authorizeEdge`). Repeating it rotates: the
    * previous certificate stays accepted until `DELETE /configuration/edges/old-certificate`,
    * so the edge can be moved to the new one without a window in which it is refused.
    */
  val setEdgeCertificateEndpoint =
    Method.PUT / "configuration" / "edges" / "certificate" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        body <- request.bodyAs[SetEdgeCertificateRequest]
        edge <- service.find(edgeId)
        response <-
          if edge.isEmpty then ZIO.succeed(Response.status(Status.NotFound))
          else service.rotateCertificate(edgeId, body.certificate).as(Response.status(Status.NoContent))
      yield response
    }

  val deleteOldEdgeCertificateEndpoint =
    Method.DELETE / "configuration" / "edges" / "old-certificate" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[EdgeService]
        edgeId <- request.url.queryZIO[EdgeId]("edgeId")
        _ <- service.deleteOldCertificate(edgeId)
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

case class RegisterEdgeRequest(
    id: EdgeId,
) derives Schema, JsonCodec

case class EdgeResponse(
    id: EdgeId,
    hasOldKey: Boolean,
    requireDpopNonce: Boolean,
    hasCertificate: Boolean,
    hasOldCertificate: Boolean,
) derives Schema, JsonCodec

case class SetEdgeCertificateRequest(
    /** One PEM-encoded X.509 certificate. */
    certificate: String,
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

case class ServiceKeyResponse(
    keyId: String,
    privateKey: String,
) derives Schema, JsonEncoder
