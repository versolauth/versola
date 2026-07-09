package versola.edge

import versola.edge.model.{Resource, ResourceEndpoint, ResourceEndpointId, ResourceId}
import versola.util.ReloadingCache
import zio.*
import zio.http.URL
import zio.test.*

import java.util.UUID

object ResourceServiceSpec extends ZIOSpecDefault:
  private val alphaEndpoint = ResourceEndpoint(
    id = ResourceEndpointId(UUID.fromString("018f0f2a-1c7b-7000-8000-000000000401")),
    method = "GET",
    path = "/users",
    fetchUserInfo = false,
    allow = None,
    inject = Vector.empty,
  )

  private val alpha = Resource(
    resourceId = ResourceId("alpha"),
    resource = URL.decode("https://alpha.example").toOption.get,
    endpoints = Vector(alphaEndpoint),
  )

  private val beta = alpha.copy(
    resourceId = ResourceId("beta"),
    resource = URL.decode("https://beta.example").toOption.get,
  )

  private def env(initial: Map[ResourceId, Resource] = Map.empty): UIO[ResourceService] =
    Ref.make(initial).map(ref => ResourceService.Impl(ReloadingCache(ref)))

  def spec = suite("edge.ResourceService")(
    test("findByResourceId returns cached resource when resourceId is known") {
      for
        service <- env(Map(ResourceId("alpha") -> alpha, ResourceId("beta") -> beta))
        result  <- service.findByResourceId(ResourceId("alpha"))
      yield assertTrue(result.contains(alpha))
    },
    test("findByResourceId returns None when resourceId is unknown") {
      for
        service <- env(Map(ResourceId("alpha") -> alpha))
        result  <- service.findByResourceId(ResourceId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("findByResourceId returns None when cache is empty") {
      for
        service <- env()
        result  <- service.findByResourceId(ResourceId("alpha"))
      yield assertTrue(result.isEmpty)
    },
  )
