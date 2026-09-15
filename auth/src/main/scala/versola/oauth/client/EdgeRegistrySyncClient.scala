package versola.oauth.client

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
trait EdgeRegistrySyncClient extends CacheSource[Map[String, JWT.PublicKeys]]:
  def getAll: Task[Map[String, JWT.PublicKeys]]

object EdgeRegistrySyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, EdgeRegistrySyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends EdgeRegistrySyncClient:
    private val RegistryURL = config.central.url / "configuration" / "edges" / "registry"

    override def getAll: Task[Map[String, JWT.PublicKeys]] =
      for
        response <- ZIO.scoped:
          centralSyncTokenService.syncRequest(Request.get(RegistryURL)).flatMap(_.bodyAs[RegistryResponse])
        keys <- ZIO.foreach(response.edges)(entry => ZIO.attempt(entry.id -> publicKeysOf(entry)))
      yield keys.map(identity).toMap

    /** Both halves of a rotation, in the order central stores them, matching how central builds
      * the same set for its own verification (`EdgeRecord.asPublicKeys`). */
    private def publicKeysOf(entry: RegistryEntry): JWT.PublicKeys =
      JWT.PublicKeys.fromJson(
        Json.Obj("keys" -> Json.Arr((entry.publicKey +: entry.oldPublicKey.toVector)*)),
      )

    private case class RegistryEntry(
        id: String,
        publicKey: Json.Obj,
        oldPublicKey: Option[Json.Obj],
    ) derives JsonCodec

    private case class RegistryResponse(
        edges: List[RegistryEntry],
    ) derives JsonCodec
