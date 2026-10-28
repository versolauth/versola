package versola.edge

import versola.util.CacheSource
import versola.util.http.{RateLimitPolicies, RateLimitPolicy}
import zio.http.{Client, Header, Request, Status}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

/** The rate limit policies central holds for this edge (`edges.rate_limit_policies`). Empty: none are set, and the edge applies
  * no rate limit.
  *
  * Central serves this edge its own row only: which edge is asking is established by the key the sync call is signed with.
  */
case class EdgeRateLimitPolicies(policies: List[RateLimitPolicy], replicas: Int)

trait RateLimitPolicySyncClient extends CacheSource[EdgeRateLimitPolicies]

object RateLimitPolicySyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService, RateLimitPolicySyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends RateLimitPolicySyncClient:
    private val RateLimitPolicyURL = config.central.url / "edge" / "sync" / "rate-limits"

    override def getAll: Task[EdgeRateLimitPolicies] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(RateLimitPolicyURL).addHeader(Header.Authorization.Bearer(token))
        policies <- ZIO.scoped:
          centralSyncTokenService.syncRequest(httpClient, request).flatMap: response =>
            // A central that predates the endpoint has no policy to give: that is "none set", not a reason for the
            // edge not to start. Everything else that is not a success fails the load like every other sync.
            if response.status == Status.NotFound then ZIO.succeed(None)
            // not decoded: an error body with no `rateLimitPolicies` in it would read as "none set"
            else if !response.status.isSuccess then
              ZIO.fail(RuntimeException(s"central answered ${response.status.code} to the rate limit policy sync"))
            else response.bodyAs[EdgeRateLimitSyncResponse].map(_.rateLimitPolicies)
      yield policies.fold(EdgeRateLimitPolicies(Nil, replicas = 1))(set => EdgeRateLimitPolicies(set.policies, set.replicas))

  private case class EdgeRateLimitSyncResponse(
      rateLimitPolicies: Option[RateLimitPolicies],
  ) derives JsonCodec
