package versola.edge

import versola.util.CacheSource
import versola.util.http.AccessLogPolicy
import zio.http.{Client, Header, Request, Status}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** Which requests this edge writes a `receive-http` line for, as central holds it for this edge (`edges.access_log_policy`):
  * mode, slow threshold and sample rate. `None`: none is set, and the edge keeps the settings of its own deployment
  * (`HTTP_ACCESS_LOG*`, or `off`).
  *
  * In central, not in this edge's environment, so an operator can turn request logging up to investigate, or down, without
  * redeploying it, and every replica of the edge gets the same answer. Central serves this edge its own row only: which edge
  * is asking is established by the key the sync call is signed with.
  */
case class EdgeAccessLogPolicy(policy: Option[AccessLogPolicy])

trait AccessLogPolicySyncClient extends CacheSource[EdgeAccessLogPolicy]

object AccessLogPolicySyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, AccessLogPolicySyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends AccessLogPolicySyncClient:
    private val AccessLogPolicyURL = config.central.url / "edge" / "sync" / "access-log"

    override def getAll: Task[EdgeAccessLogPolicy] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(AccessLogPolicyURL).addHeader(Header.Authorization.Bearer(token))
        policy <- ZIO.scoped:
          centralSyncTokenService.syncRequest(httpClient, request).flatMap: response =>
            // A central that predates the endpoint has no policy to give: that is "none set", not a reason for the
            // edge not to start. Everything else that is not a success fails the load like every other sync.
            if response.status == Status.NotFound then ZIO.succeed(None)
            // not decoded: an error body with no `accessLogPolicy` in it would read as "none set"
            else if !response.status.isSuccess then
              ZIO.fail(RuntimeException(s"central answered ${response.status.code} to the overload policy sync"))
            else response.bodyAs[EdgeAccessLogSyncResponse].map(_.accessLogPolicy)
      yield EdgeAccessLogPolicy(policy)

  private case class EdgeAccessLogSyncResponse(
      accessLogPolicy: Option[AccessLogPolicy],
  ) derives JsonCodec
