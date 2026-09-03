package versola.oauth.client

import versola.oauth.client.model.{ClientId, ResourceId, ResourceRecord, ResourceUri, TenantId}
import versola.util.{Base64, CacheSource, CoreConfig, Secret, SecurityService}
import zio.http.Request
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** Fetches the lightweight resource registry (id, tenant, URI) from central, used to
  * validate the RFC 8707 `resource` request parameter. The response also carries the secret
  * of auth's own internal resource (hard-coded resourceId `"auth"`, matching central's
  * `BootstrapService.authResourceId`),
  * encrypted with the central secret key the same way as edge's resource sync (see
  * [[ResourceSyncClient.decryptSecret]]), so auth can authenticate that resource without a
  * separately configured static secret.
  */
trait ResourceSyncClient extends CacheSource[ResourceSyncClient.SyncResult]:
  def getAll: Task[ResourceSyncClient.SyncResult]

object ResourceSyncClient:
  /** `resources` is the full registry, used for RFC 8707 `resource` parameter validation.
    * `authResourceSecret` is pulled out of that same response for the "auth" entry only. */
  case class SyncResult(
      resources: Vector[ResourceRecord],
      authResourceSecret: Option[Secret],
  )

  val live: URLayer[CoreConfig & SecurityService & CentralSyncTokenService, ResourceSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      config: CoreConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends ResourceSyncClient:
    private val RegistryURL = config.central.url / "configuration" / "resources" / "registry"

    override def getAll: Task[SyncResult] =
      for
        response <- ZIO.scoped:
          centralSyncTokenService.syncRequest(Request.get(RegistryURL)).flatMap(_.bodyAs[RegistryResponse])
        records = response.resources.map(r => ResourceRecord(r.resourceId, r.tenantId, r.resource, r.audience, r.internal))
        authSecret <- ZIO.foreach(response.authResourceSecret)(decryptSecret)
      yield SyncResult(records, authSecret)

    private def decryptSecret(value: String): Task[Secret] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        decrypted <- securityService.decryptAes256(encrypted, config.central.secretKey)
      yield Secret(decrypted)

    private case class RegistryEntry(
        resourceId: ResourceId,
        tenantId: TenantId,
        resource: ResourceUri,
        audience: List[ClientId],
        internal: Boolean,
    ) derives JsonCodec

    private case class RegistryResponse(
        resources: Vector[RegistryEntry],
        authResourceSecret: Option[String],
    ) derives JsonCodec
