package versola.edge

import versola.util.CacheSource
import versola.util.http.OverloadPolicy
import zio.http.{Client, Header, Request, Status}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** What this edge does when asked for more than it can do, as central holds it for this edge (`edges.overload_policy`): its
  * inbound admission limits and the bounds of its outbound calls. `None`: none is set, and the edge keeps the settings of its
  * own deployment (`HTTP_ADMISSION_*`, `HTTP_CLIENT_GUARD_*`).
  *
  * In central, not in this edge's environment, so an operator can tighten or loosen it while the edge is under load without
  * redeploying it, and every replica of the edge gets the same answer. Central serves this edge its own row only: which edge
  * is asking is established by the key the sync call is signed with.
  */
case class EdgeOverloadPolicy(policy: Option[OverloadPolicy])

trait OverloadPolicySyncClient extends CacheSource[EdgeOverloadPolicy]

object OverloadPolicySyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, OverloadPolicySyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OverloadPolicySyncClient:
    private val OverloadPolicyURL = config.central.url / "edge" / "sync" / "overload"

    override def getAll: Task[EdgeOverloadPolicy] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(OverloadPolicyURL).addHeader(Header.Authorization.Bearer(token))
        policy <- ZIO.scoped:
          centralSyncTokenService.syncRequest(httpClient, request).flatMap: response =>
            // A central that predates the endpoint has no policy to give: that is "none set", not a reason for the
            // edge not to start. Everything else that is not a success fails the load like every other sync.
            if response.status == Status.NotFound then ZIO.succeed(None)
            // not decoded: an error body with no `overloadPolicy` in it would read as "none set"
            else if !response.status.isSuccess then
              ZIO.fail(RuntimeException(s"central answered ${response.status.code} to the overload policy sync"))
            else response.bodyAs[EdgeOverloadSyncResponse].map(_.overloadPolicy)
      yield EdgeOverloadPolicy(policy)

  private case class EdgeOverloadSyncResponse(
      overloadPolicy: Option[OverloadPolicy],
  ) derives JsonCodec
