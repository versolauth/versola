package versola.edge

import versola.edge.model.{Resource, ResourceEndpoint, ResourceEndpointId}
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
    id = versola.edge.model.ResourceId(1),
    alias = "alpha",
    resource = URL.decode("https://alpha.example").toOption.get,
    endpoints = Vector(alphaEndpoint),
  )

  private val beta = alpha.copy(
    id = versola.edge.model.ResourceId(2),
    alias = "beta",
    resource = URL.decode("https://beta.example").toOption.get,
  )

  private def env(initial: Map[String, Resource] = Map.empty): UIO[ResourceService] =
    Ref.make(initial).map(ref => ResourceService.Impl(ReloadingCache(ref)))

  def spec = suite("edge.ResourceService")(
    test("findByAlias returns cached resource when alias is known") {
      for
        service <- env(Map("alpha" -> alpha, "beta" -> beta))
        result  <- service.findByAlias("alpha")
      yield assertTrue(result.contains(alpha))
    },
    test("findByAlias returns None when alias is unknown") {
      for
        service <- env(Map("alpha" -> alpha))
        result  <- service.findByAlias("missing")
      yield assertTrue(result.isEmpty)
    },
    test("findByAlias returns None when cache is empty") {
      for
        service <- env()
        result  <- service.findByAlias("alpha")
      yield assertTrue(result.isEmpty)
    },
  )
