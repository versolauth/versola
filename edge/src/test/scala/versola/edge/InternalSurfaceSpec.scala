package versola.edge

import io.opentelemetry.api
import org.scalamock.stubs.ZIOStubs
import versola.edge.dpop.DpopPolicyService
import versola.edge.model.EdgeId
import versola.util.http.Observability
import versola.util.{EnvName, Secret}
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator

/** Boundary 3 of #555: which mode serves the internal surface at all. What the surface does
  * once reached is `ServiceControllerSpec`'s subject; here a request only has to find it, or
  * not.
  */
object InternalSurfaceSpec extends ZIOSpecDefault, ZIOStubs:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private def config(mode: EdgeMode): EdgeConfig = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "edge-key",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
      internalSecret = Some(Secret(Array.fill(32)(4.toByte))),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
    mode = mode,
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  private val syncRequest = Request.post(URL.empty / "service" / "configuration" / "sync", Body.empty)

  private def serve(mode: EdgeMode, request: Request): ZIO[TestClient & Client & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          InternalSurface.routes(mode).provideEnvironment(
            ZEnvironment[OAuthClientService](stub[OAuthClientService]) ++
              ZEnvironment[ResourceService](stub[ResourceService]) ++
              ZEnvironment[PermissionService](stub[PermissionService]) ++
              ZEnvironment[DpopPolicyService](stub[DpopPolicyService]) ++
              ZEnvironment[EdgeConfig](config(mode)) ++
              ZEnvironment[EnvName](EnvName.Test("test")) ++
              tracing,
          ),
        ),
      )
      response <- client.batched(request)
    yield response

  def spec = suite("InternalSurface")(
    // Found and refused, not absent: the surface is mounted, and it is the controller's own
    // check that turns an unauthenticated caller away.
    test("first-party mounts the configuration sync") {
      for response <- serve(EdgeMode.FirstParty, syncRequest)
      yield assertTrue(response.status == Status.Unauthorized)
    },
    test("partner mounts nothing, so the sync path matches no route") {
      for response <- serve(EdgeMode.Partner, syncRequest)
      yield assertTrue(
        response.status == Status.NotFound,
        InternalSurface.routes(EdgeMode.Partner).routes.isEmpty,
      )
    },
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
