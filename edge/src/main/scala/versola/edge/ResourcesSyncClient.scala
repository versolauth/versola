package versola.edge

import versola.edge.model.Resource.given
import versola.edge.model.{Resource, ResourceEndpoint, ResourceId}
import versola.util.{Base64, CacheSource, Secret, SecurityService}
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait ResourcesSyncClient extends CacheSource[Map[ResourceId, Resource]]:
  def getAll: Task[Map[ResourceId, Resource]]

object ResourcesSyncClient:
  val live: URLayer[Client & EdgeConfig & SecurityService & CentralSyncTokenService, ResourcesSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ResourcesSyncClient:
    private val ResourcesURL = config.central.url / "configuration" / "resources" / "sync"

    override def getAll: Task[Map[ResourceId, Resource]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ResourcesURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        response <- response.bodyAs[GetResourcesSyncResponse]
        resources <- ZIO.foreach(response.resources) { resource =>
          ZIO.foreach(resource.secret)(decryptSecret).map { secret =>
            Resource(resource.resourceId, resource.resource, resource.endpoints, secret)
          }
        }
      yield resources.map(x => x.resourceId -> x).toMap

    private def decryptSecret(value: String): Task[Secret] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        decrypted <- securityService.decryptRsa(encrypted, config.privateKey)
      yield Secret(decrypted)

    private case class SyncResource(
        resourceId: ResourceId,
        resource: zio.http.URL,
        endpoints: Vector[ResourceEndpoint],
        secret: Option[String],
    ) derives JsonCodec

    private case class GetResourcesSyncResponse(
        resources: Vector[SyncResource],
    ) derives JsonCodec
