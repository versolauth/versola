package versola.edge

import versola.edge.model.Resource.given
import versola.edge.model.{Resource, ResourceEndpoint, ResourceId}
import versola.util.{Base64, CacheSource, Secret, SecurityService}
import versola.util.cel.CelEvaluator
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait ResourcesSyncClient extends CacheSource[Map[ResourceId, Resource]]:
  def getAll: Task[Map[ResourceId, Resource]]

object ResourcesSyncClient:
  val live: URLayer[Client & EdgeConfig & SecurityService & CentralSyncTokenService & CelEvaluator, ResourcesSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
      celEvaluator: CelEvaluator,
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
        _ <- precompileRules(resources)
      yield resources.map(x => x.resourceId -> x).toMap

    /** Warms `celEvaluator`'s compile cache for every rule this sync just fetched, so the
      * compile happens here, on load and on every refresh, rather than on whichever request
      * happens to hit an endpoint's rule first. `compile` is keyed by expression string and
      * never fails, so calling it again for an expression already cached, or for one that
      * turns out not to compile, is a cheap no-op / one-time warning, not a request-path risk.
      */
    private def precompileRules(resources: Vector[Resource]): Task[Unit] =
      ZIO.foreachDiscard(resources): resource =>
        ZIO.foreachDiscard(resource.endpoints): endpoint =>
          ZIO.foreachDiscard(endpoint.allow.filter(_.trim.nonEmpty))(celEvaluator.compile) *>
            ZIO.foreachDiscard(endpoint.stepUpCondition)(celEvaluator.compile) *>
            ZIO.foreachDiscard(endpoint.inject)(rule => celEvaluator.compile(rule.expression))

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
