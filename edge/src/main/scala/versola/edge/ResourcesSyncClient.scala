package versola.edge

import versola.edge.model.Resource
import versola.util.CacheSource
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait ResourcesSyncClient extends CacheSource[Map[String, Resource]]:
  def getAll: Task[Map[String, Resource]]

object ResourcesSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, ResourcesSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ResourcesSyncClient:
    private val ResourcesURL = config.central.url / "configuration" / "resources" / "sync"

    override def getAll: Task[Map[String, Resource]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ResourcesURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        response <- response.bodyAs[GetResourcesSyncResponse]
      yield response.resources.map(x => x.alias -> x).toMap

    private case class GetResourcesSyncResponse(
        resources: Vector[Resource],
    ) derives JsonCodec
