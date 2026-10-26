package versola.edge

import versola.util.http.{DeclaredLimit, DeclaredOwner}
import zio.{UIO, ZIO, ZLayer}

/** The rate limits the owner of an API declares: on a resource endpoint, and on a client of a resource's audience (levels 2 and 3
  * of issue #744 section 4). Derived from the resources the edge already syncs, so a change in central reaches the limiter by
  * the refresh of the resources, and nothing here is read while handling a request: [[RateLimitPolicyApplier]] polls
  * [[current]] and hands the result to the limiter, which keeps the buckets of a declaration whose shape did not change.
  */
trait DeclaredRateLimitService:
  /** Every limit declared now: endpoints first, then audience clients, each in a stable order so an unchanged sync is no change. */
  def current: UIO[List[DeclaredLimit]]

object DeclaredRateLimitService:
  val live: ZLayer[ResourceService, Nothing, DeclaredRateLimitService] =
    ZLayer.fromFunction(Impl(_))

  /** None declared (tests, tools). */
  val none: zio.ULayer[DeclaredRateLimitService] =
    ZLayer.succeed(new DeclaredRateLimitService:
      override val current: UIO[List[DeclaredLimit]] = ZIO.succeed(Nil))

  class Impl(resources: ResourceService) extends DeclaredRateLimitService:

    override def current: UIO[List[DeclaredLimit]] =
      resources.all.map: all =>
        val sorted = all.toList.sortBy(_.resourceId.toString)
        val endpoints =
          for
            resource <- sorted
            endpoint <- resource.endpoints.toList
            limit <- endpoint.rateLimit
          yield DeclaredLimit(owner = DeclaredOwner.Endpoint, selector = endpoint.template(resource.resourceId), limit = limit)
        val audiences =
          for
            resource <- sorted
            declared <- resource.audienceRateLimits.sortBy(_.clientId.toString)
          yield DeclaredLimit(
            owner = DeclaredOwner.Audience,
            selector = declared.clientId.toString,
            limit = declared.rateLimit,
            resource = resource.resourceId.toString,
          )
        endpoints ++ audiences
