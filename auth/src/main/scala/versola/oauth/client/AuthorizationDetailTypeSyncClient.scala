package versola.oauth.client

import versola.oauth.client.model.AuthorizationDetailTypeRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** Fetches the RFC 9396 authorization detail type registry from central: the per-tenant
  * vocabulary of `type` values clients may request and the JSON Schema each detail object
  * of that type must satisfy.
  */
trait AuthorizationDetailTypeSyncClient extends CacheSource[Vector[AuthorizationDetailTypeRecord]]:
  def getAll: Task[Vector[AuthorizationDetailTypeRecord]]

object AuthorizationDetailTypeSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, AuthorizationDetailTypeSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends AuthorizationDetailTypeSyncClient:
    private val TypesURL = config.central.url / "configuration" / "authorization-detail-types" / "sync"

    override def getAll: Task[Vector[AuthorizationDetailTypeRecord]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(TypesURL)).flatMap(_.bodyAs[TypesResponse])
      .map(_.types)

  case class TypesResponse(types: Vector[AuthorizationDetailTypeRecord]) derives JsonCodec
