package versola.edge

import versola.edge.model.{ClientId, Resource, ResourceEndpoint, ResourceEndpointId, ResourceId}
import versola.util.ReloadingCache
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

  private val api = Resource(
    resourceId = ResourceId("api"),
    resource = URL.decode("https://api.example").toOption.get,
    endpoints = Vector(orders, health),
    secret = None,
  )

  private def resources(known: Resource*): ResourceService =
    new ResourceService:
      override def findByResourceId(resourceId: ResourceId) = ZIO.succeed(known.find(_.resourceId == resourceId))
      override val all: UIO[Vector[Resource]] = ZIO.succeed(Vector.from(known))
      override val refreshNow: Task[Unit] = ZIO.unit

  private def clientLimits(fromCentral: Map[ClientId, DeclaredRateLimit]): ClientRateLimitsSyncClient =
    new ClientRateLimitsSyncClient:
      override def getAll: Task[Map[ClientId, DeclaredRateLimit]] = ZIO.succeed(fromCentral)

  private def service(
      resourceList: Seq[Resource] = Nil,
      cached: Map[ClientId, DeclaredRateLimit] = Map.empty,
      fromCentral: Map[ClientId, DeclaredRateLimit] = Map.empty,
  ): UIO[DeclaredRateLimitService] =
    Ref.make(cached).map(ref => DeclaredRateLimitService.Impl(ReloadingCache(ref), clientLimits(fromCentral), resources(resourceList*)))

  def spec = suite("DeclaredRateLimitService")(
    test("nothing declared, nothing derived") {
      for
        s <- service(resourceList = List(api.copy(endpoints = Vector(health))))
        current <- s.current
      yield assertTrue(current.isEmpty)
    },
    test("an endpoint's limit is found by the same template R3 keys on, and an endpoint without one adds nothing") {
      for
        s <- service(resourceList = List(api))
        current <- s.current
      yield assertTrue(
        current == List(DeclaredLimit(owner = DeclaredOwner.Endpoint, selector = "GET /resources/api/orders/{id}", limit = perMinute)),
        current.head.selector == orders.template(ResourceId("api")),
      )
    },
    test("a client's limit is found by its id, whether or not the edge holds a credential for it") {
      for
        s <- service(cached = Map(ClientId("public-spa") -> perMinute))
        current <- s.current
      yield assertTrue(current == List(DeclaredLimit(owner = DeclaredOwner.Client, selector = "public-spa", limit = perMinute)))
    },
    test("the result does not depend on the order the maps happen to iterate in, so an unchanged sync is not a change") {
      val other = api.copy(resourceId = ResourceId("aaa"))
      for
        a <- service(resourceList = List(api, other), cached = Map(ClientId("b") -> perMinute, ClientId("a") -> perMinute))
        b <- service(resourceList = List(other, api), cached = Map(ClientId("a") -> perMinute, ClientId("b") -> perMinute))
        first <- a.current
        second <- b.current
      yield assertTrue(first == second, first.map(_.selector).take(2) == List("GET /resources/aaa/orders/{id}", "GET /resources/api/orders/{id}"))
    },
    test("refreshNow loads the client limits from central, and a limit removed there is gone") {
      for
        s <- service(cached = Map(ClientId("old") -> perMinute), fromCentral = Map(ClientId("new") -> perMinute))
        before <- s.current
        _ <- s.refreshNow
        after <- s.current
      yield assertTrue(before.map(_.selector) == List("old"), after.map(_.selector) == List("new"))
    },
  )
