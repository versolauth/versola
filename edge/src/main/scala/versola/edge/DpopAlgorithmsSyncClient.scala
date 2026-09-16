package versola.edge

import versola.util.{CacheSource, Dpop}
import zio.http.{Client, Header, Request}
import zio.json.ast.Json
import zio.{Task, URLayer, ZIO, ZLayer}

/** RFC 9449 §5.1: the signing algorithms a proxied call's proof may use, read off the
  * authorization server metadata document central stores.
  *
  * The document is the only place the set is written down -- `auth` serves it and checks its own
  * proofs against it, and this edge checks the proofs it sees against the same field. An edge
  * list of its own would be a second copy of one decision, and a proof accepted at the token
  * endpoint but refused one hop later is the shape that drift takes.
  */
trait DpopAlgorithmsSyncClient extends CacheSource[Set[Dpop.Algorithm]]

object DpopAlgorithmsSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, DpopAlgorithmsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends DpopAlgorithmsSyncClient:
    private val MetadataURL = config.central.url / "configuration" / "server-metadata" / "sync"

    override def getAll: Task[Set[Dpop.Algorithm]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(MetadataURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        document <- response.body.asJsonFromCodec[Json.Obj]
      yield Dpop.Algorithm.fromMetadata(document)
