package versola.edge

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.edge.model.EdgeId
import versola.util.{Base64Url, EnvName, Secret}
import versola.util.http.Observability
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*
import io.opentelemetry.api

import java.security.KeyPairGenerator

object ServiceControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val internalSecret = Secret(Array.fill(32)(4.toByte))

  // Positional args through `privateKey` avoid a named `privateKey = ...` argument.
  private def edgeConfig(secret: Option[Secret]): EdgeConfig = EdgeConfig(
    EdgeId("edge-1"),
    "edge-key",
    keyPair.getPrivate.nn,
    EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
      internalSecret = secret,
    ),
    EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    URL.decode("https://idp.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](
      Tracing.live(logAnnotated = false),
      OpenTelemetry.contextZIO,
      ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")),
    )

  /** Every cache the endpoint refreshes, so a test can catch one being left out. */
  private case class Services(
      clients: Stub[OAuthClientService],
      resources: Stub[ResourceService],
      permissions: Stub[PermissionService],
  ):
    def refreshCalls: List[Int] =
      List(clients.refreshNow.calls.length, resources.refreshNow.calls.length, permissions.refreshNow.calls.length)

  private def run(
      request: Request,
      config: EdgeConfig = edgeConfig(Some(internalSecret)),
      env: EnvName = EnvName.Test("test"),
      setup: Services => UIO[Unit] = _ => ZIO.unit,
  ): ZIO[TestClient & Client & Scope, Throwable, (Response, Services)] =
    for
      client  <- ZIO.service[Client]
      services =  Services(stub[OAuthClientService], stub[ResourceService], stub[PermissionService])
      tracing <- tracingLayer.build
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          ServiceController.routes.provideEnvironment(
            ZEnvironment[OAuthClientService](services.clients) ++
              ZEnvironment[ResourceService](services.resources) ++
              ZEnvironment[PermissionService](services.permissions) ++
              ZEnvironment[EdgeConfig](config) ++
              ZEnvironment[EnvName](env) ++
              tracing,
          ),
        ),
      )
      _        <- setup(services)
      response <- client.batched(request)
    yield (response, services)

  private val refreshesSucceed: Services => UIO[Unit] = services =>
    services.clients.refreshNow.succeedsWith(()) *>
      services.resources.refreshNow.succeedsWith(()) *>
      services.permissions.refreshNow.succeedsWith(())

  private def syncRequest(auth: Option[Header.Authorization]): Request =
    val base = Request.post(URL.empty / "service" / "configuration" / "sync", Body.empty)
    auth.fold(base)(base.addHeader(_))

  private val validAuth = Header.Authorization.Basic("edge", Base64Url.encode(internalSecret))

  def spec = suite("ServiceController")(
    suite("POST /service/configuration/sync")(
      test("refreshes every configuration cache and returns 200 OK when the internal secret matches") {
        for
          (response, services) <- run(syncRequest(Some(validAuth)), setup = refreshesSucceed)
        yield assertTrue(response.status == Status.Ok, services.refreshCalls == List(1, 1, 1))
      },
      test("fails the request when one cache cannot be refreshed, rather than reporting a partial sync") {
        for
          (response, _) <- run(
            syncRequest(Some(validAuth)),
            setup = services =>
              services.clients.refreshNow.succeedsWith(()) *>
                services.resources.refreshNow.failsWith(RuntimeException("central unreachable")) *>
                services.permissions.refreshNow.succeedsWith(()),
          )
        yield assertTrue(response.status == Status.InternalServerError)
      },
      test("rejects a request with no Authorization header") {
        for
          (response, services) <- run(syncRequest(None))
        yield assertTrue(response.status == Status.Unauthorized, services.refreshCalls == List(0, 0, 0))
      },
      test("rejects a request with the wrong username") {
        val wrongAuth = Header.Authorization.Basic("central", Base64Url.encode(internalSecret))
        for
          (response, services) <- run(syncRequest(Some(wrongAuth)))
        yield assertTrue(response.status == Status.Unauthorized, services.refreshCalls == List(0, 0, 0))
      },
      test("rejects a request whose secret does not match") {
        val wrongAuth = Header.Authorization.Basic("edge", Base64Url.encode(Secret(Array.fill(32)(9.toByte))))
        for
          (response, services) <- run(syncRequest(Some(wrongAuth)))
        yield assertTrue(response.status == Status.Unauthorized, services.refreshCalls == List(0, 0, 0))
      },
      test("rejects a request whose secret is not valid base64url") {
        val malformedAuth = Header.Authorization.Basic("edge", "not-valid-base64!!!")
        for
          (response, services) <- run(syncRequest(Some(malformedAuth)))
        yield assertTrue(response.status == Status.Unauthorized, services.refreshCalls == List(0, 0, 0))
      },
      test("rejects every request when the config has no internal secret configured") {
        for
          (response, services) <- run(
            syncRequest(Some(validAuth)),
            config = edgeConfig(secret = None),
          )
        yield assertTrue(response.status == Status.Unauthorized, services.refreshCalls == List(0, 0, 0))
      },
      test("returns 404 Not Found in prod, without even checking auth") {
        for
          (response, services) <- run(syncRequest(None), env = EnvName.Prod)
        yield assertTrue(response.status == Status.NotFound, services.refreshCalls == List(0, 0, 0))
      },
    ),
  ).provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging
