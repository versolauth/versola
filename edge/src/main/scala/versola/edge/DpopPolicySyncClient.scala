package versola.edge

import versola.util.CacheSource
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** RFC 9449 §9 at this edge: whether every proof it checks on a proxied call must carry a nonce
  * it issued.
  *
  * Central holds it per edge (`edges.require_dpop_nonce`) rather than this edge's own config
  * holding it, so an operator can turn it off for one edge from the console without a redeploy,
  * and so the answer is the same on every replica of that edge at the same moment. Central
  * serves this edge its own row only -- which edge is asking is established by the key the sync
  * call is signed with, not by anything in the request.
  */
case class DpopPolicy(requireNonce: Boolean)

trait DpopPolicySyncClient extends CacheSource[DpopPolicy]

object DpopPolicySyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, DpopPolicySyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends DpopPolicySyncClient:
    private val DpopPolicyURL = config.central.url / "configuration" / "edges" / "dpop" / "sync"

    override def getAll: Task[DpopPolicy] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(DpopPolicyURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        body <- response.bodyAs[EdgeDpopSyncResponse]
      yield DpopPolicy(requireNonce = body.requireDpopNonce)

  private case class EdgeDpopSyncResponse(
      requireDpopNonce: Boolean,
  ) derives JsonCodec
