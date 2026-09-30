package versola.oauth.client

import versola.oauth.client.model.AuthorizationDetailTypeRecord
import versola.util.{CacheSource, CoreConfig, JsonSchemaValidator}
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
  val live: URLayer[CoreConfig & CentralSyncTokenService & JsonSchemaValidator, AuthorizationDetailTypeSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
      schemaValidator: JsonSchemaValidator,
  ) extends AuthorizationDetailTypeSyncClient:
    private val TypesURL = config.central.url / "configuration" / "authorization-detail-types" / "sync"

    override def getAll: Task[Vector[AuthorizationDetailTypeRecord]] =
      for
        response <- ZIO.scoped(
          centralSyncTokenService.syncRequest(Request.get(TypesURL)).flatMap(_.bodyAs[TypesResponse]),
        )
        types = response.types
        // Warms the schema compile cache for every type this sync just fetched -- on load and
        // on every refresh -- rather than leaving the first request whose `authorization_details`
        // uses a given type to pay for the networknt compile (see AuthorizationDetailResolver).
        // Mirrors edge's ResourcesSyncClient.precompileRules for the same reason.
        _ <- ZIO.foreachDiscard(types)(record => schemaValidator.warmCompile(record.schema))
      yield types

  case class TypesResponse(types: Vector[AuthorizationDetailTypeRecord]) derives JsonCodec
