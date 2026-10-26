package versola.edge

import versola.edge.model.{AudienceRateLimit, ClientId, Resource, ResourceEndpoint, ResourceEndpointId, ResourceId}
import versola.util.http.{DeclaredLimit, DeclaredOwner, DeclaredRateLimit, DeclaredRateLimitBy, GuardMode, RateLimitWindow}
import zio.*
import zio.http.URL
import zio.test.*

import java.util.UUID

object DeclaredRateLimitServiceSpec extends ZIOSpecDefault:

  private val perMinute = DeclaredRateLimit(
    limits = List(RateLimitWindow(limit = 60, windowSeconds = 60)),
    burst = 10,
    by = DeclaredRateLimitBy.Subject,
    mode = GuardMode.Enforce,
  )

  private def endpoint(n: Int, path: String, rateLimit: Option[DeclaredRateLimit]) =
    ResourceEndpoint(
      id = ResourceEndpointId(UUID.fromString(s"018f0f2a-1c7b-7000-8000-00000000060$n")),
      method = "GET",
      path = path,
      fetchUserInfo = false,
      allow = None,
      inject = Vector.empty,
      stepUpCondition = None,
      stepUpAcr = None,
      maxAge = None,
      rateLimit = rateLimit,
    )

  private val orders = endpoint(1, "/orders/{id}", Some(perMinute))
  private val health = endpoint(2, "/health", None)

  private def resource(id: String, endpoints: Vector[ResourceEndpoint], audience: List[AudienceRateLimit] = Nil) =
    Resource(
      resourceId = ResourceId(id),
      resource = URL.decode(s"https://$id.example").toOption.get,
      endpoints = endpoints,
      secret = None,
      audienceRateLimits = audience,
    )

  private val api = resource("api", Vector(orders, health))

  private def service(resources: Resource*): DeclaredRateLimitService =
    DeclaredRateLimitService.Impl(new ResourceService:
      override def findByResourceId(resourceId: ResourceId) = ZIO.succeed(resources.find(_.resourceId == resourceId))
      override val all: UIO[Vector[Resource]] = ZIO.succeed(Vector.from(resources))
      override val refreshNow: Task[Unit] = ZIO.unit)

  def spec = suite("DeclaredRateLimitService")(
    test("nothing declared, nothing derived") {
      for current <- service(resource("api", Vector(health))).current
      yield assertTrue(current.isEmpty)
    },
    test("an endpoint's limit is found by the same template R3 keys on, and an endpoint without one adds nothing") {
      for current <- service(api).current
      yield assertTrue(
        current == List(DeclaredLimit(owner = DeclaredOwner.Endpoint, selector = "GET /resources/api/orders/{id}", limit = perMinute)),
        current.head.selector == orders.template(ResourceId("api")),
      )
    },
    test("a limit the resource sets for an audience client is found by the resource and the client id, as R3 keys them") {
      val plan = AudienceRateLimit(ClientId("plan-a"), perMinute)
      for current <- service(resource("api", Vector(health), List(plan))).current
      yield assertTrue(current == List(DeclaredLimit(owner = DeclaredOwner.Audience, selector = "plan-a", limit = perMinute, resource = "api")))
    },
    test("the same client on two resources has a limit on each, and the endpoints come before the audience limits") {
      val plan = AudienceRateLimit(ClientId("plan-a"), perMinute)
      for current <- service(resource("b-api", Vector(health), List(plan)), resource("a-api", Vector(orders), List(plan))).current
      yield assertTrue(
        current.map(l => (l.owner, l.resource, l.selector)) == List(
          (DeclaredOwner.Endpoint, "", "GET /resources/a-api/orders/{id}"),
          (DeclaredOwner.Audience, "a-api", "plan-a"),
          (DeclaredOwner.Audience, "b-api", "plan-a"),
        ),
      )
    },
    test("the result does not depend on the order the resources and clients come in, so an unchanged sync is not a change") {
      val a = AudienceRateLimit(ClientId("a"), perMinute)
      val b = AudienceRateLimit(ClientId("b"), perMinute)
      for
        first <- service(resource("x", Vector(orders), List(a, b)), resource("y", Vector(orders), List(b, a))).current
        second <- service(resource("y", Vector(orders), List(a, b)), resource("x", Vector(orders), List(b, a))).current
      yield assertTrue(first == second)
    },
  )
