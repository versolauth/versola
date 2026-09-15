package versola.oauth.client

import versola.oauth.client.model.TenantId
import versola.util.{CacheSource, CoreConfig, JWT}
import zio.http.Request
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** Fetches the registered signing key of every edge from central, so auth can authenticate an
  * edge that calls it on its own behalf (see `versola.util.EdgeAssertion`).
  *
  * Central holds the edges registry -- it is what issues an edge its key pair and what
  * verifies an edge's own sync calls -- and auth is not on that database. This mirrors
  * [[ResourceSyncClient]]: same endpoint shape, same sync credentials, refreshed on the same
  * configuration interval as every other registry auth keeps a copy of.
  */
trait EdgeRegistrySyncClient extends CacheSource[Map[String, EdgeRegistrySyncClient.EdgeRegistration]]:
  def getAll: Task[Map[String, EdgeRegistrySyncClient.EdgeRegistration]]

object EdgeRegistrySyncClient:
  /** An edge's registered keys, alongside the tenants central has assigned it to serve.
    *
    * `tenantIds` is what an assertion's identity check alone cannot give: a correctly signed
    * assertion still names an edge that may simply not be the one behind the tenant a given
    * token belongs to, and any registered edge's key would otherwise vouch for any tenant's
    * tokens.
    */
  case class EdgeRegistration(keys: JWT.PublicKeys, tenantIds: Set[TenantId])

  val live: URLayer[CoreConfig & CentralSyncTokenService, EdgeRegistrySyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends EdgeRegistrySyncClient:
    private val RegistryURL = config.central.url / "configuration" / "edges" / "registry"

    override def getAll: Task[Map[String, EdgeRegistration]] =
      for
        response <- ZIO.scoped:
          centralSyncTokenService.syncRequest(Request.get(RegistryURL)).flatMap(_.bodyAs[RegistryResponse])
        registrations <- ZIO.foreach(response.edges)(entry => ZIO.attempt(entry.id -> registrationOf(entry)))
      yield registrations.map(identity).toMap

    /** Both halves of a rotation, in the order central stores them, matching how central builds
      * the same set for its own verification (`EdgeRecord.asPublicKeys`), alongside the
      * tenants central has this edge assigned to. */
    private def registrationOf(entry: RegistryEntry): EdgeRegistration =
      EdgeRegistration(
        keys = JWT.PublicKeys.fromJson(
          Json.Obj("keys" -> Json.Arr((entry.publicKey +: entry.oldPublicKey.toVector)*)),
        ),
        tenantIds = entry.tenantIds.map(TenantId(_)).toSet,
      )

    private case class RegistryEntry(
        id: String,
        publicKey: Json.Obj,
        oldPublicKey: Option[Json.Obj],
        tenantIds: List[String] = List.empty,
    ) derives JsonCodec

    private case class RegistryResponse(
        edges: List[RegistryEntry],
    ) derives JsonCodec
