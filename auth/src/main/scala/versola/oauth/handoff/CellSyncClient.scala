package versola.oauth.handoff

import versola.oauth.client.CentralSyncTokenService
import versola.util.{CacheSource, CoreConfig}
import zio.http.Request
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.{Task, URLayer, ZIO, ZLayer}

/** The cells central knows, as it answers `/configuration/cells/sync` to an auth. */
trait CellSyncClient extends CacheSource[Vector[CellInfo]]

/** One cell as central describes it to an auth (versolauth/versola#1050).
  *
  * @param publicKeys the public RSA JWKs the cell is known by: one, or two while its key is being replaced. The first is
  *   the one to encrypt to.
  */
case class CellInfo(
    id: String,
    state: String,
    weight: Int,
    regionId: String,
    publicKeys: List[Json.Obj],
) derives JsonCodec

object CellSyncClient:
  val live: URLayer[CoreConfig & CentralSyncTokenService, CellSyncClient] =
    ZLayer.fromFunction(Impl(_, _))

  private case class CellsResponse(cells: Vector[CellInfo]) derives JsonCodec

  class Impl(config: CoreConfig, centralSyncTokenService: CentralSyncTokenService) extends CellSyncClient:
    private val CellsURL = config.central.url / "configuration" / "cells" / "sync"

    override def getAll: Task[Vector[CellInfo]] =
      ZIO.scoped:
        centralSyncTokenService.syncRequest(Request.get(CellsURL))
          .flatMap(_.body.asJsonFromCodec[CellsResponse])
          .map(_.cells)
