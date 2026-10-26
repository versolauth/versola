package versola.edge

import org.scalamock.stubs.ZIOStubs
import versola.edge.model.*
import versola.util.{PrivateClientCertificate, Secret, TestCertificates}
import zio.*
import zio.test.*

object SSOClientWarmUpSpec extends ZIOSpecDefault, ZIOStubs:

  private val certificateMaterial: PrivateClientCertificate.Material =
    PrivateClientCertificate(TestCertificates.generate().bundle).material.toOption.get

  private def mutualTls(id: String) = OAuthClient(
    id = ClientId(id),
    credential = ClientCredential.MutualTls(certificateMaterial),
    permissions = Set.empty,
    accessTokenTtl = 15.minutes,
  )

  private val secretClient = OAuthClient(
    id = ClientId("a-web-app"),
    credential = ClientCredential.ClientSecret(Secret("s3cret".getBytes("UTF-8").nn)),
    permissions = Set.empty,
    accessTokenTtl = 15.minutes,
  )

  def spec = suite("SSOClientWarmUp")(
    test("warms the certificate client that sorts first, and only that one") {
      val sso = stub[SSOClient]
      for
        _ <- sso.warmUp.succeedsWith(())
        _ <- SSOClientWarmUp.warmUp(sso, Seq(mutualTls("c-client"), mutualTls("b-client"), mutualTls("d-client")))
      yield assertTrue(sso.warmUp.calls.map(_.id) == List(ClientId("b-client")))
    },
    test("leaves a client that authenticates in the request alone, and warms the certificate one beside it") {
      val sso = stub[SSOClient]
      for
        _ <- sso.warmUp.succeedsWith(())
        _ <- SSOClientWarmUp.warmUp(sso, Seq(secretClient, mutualTls("z-client")))
      yield assertTrue(sso.warmUp.calls.map(_.id) == List(ClientId("z-client")))
    },
    test("does nothing when no client holds a certificate") {
      val sso = stub[SSOClient]
      for _ <- SSOClientWarmUp.warmUp(sso, Seq(secretClient))
      yield assertTrue(sso.warmUp.calls.isEmpty)
    },
    test("does nothing when edge knows no client at all") {
      val sso = stub[SSOClient]
      for _ <- SSOClientWarmUp.warmUp(sso, Seq.empty)
      yield assertTrue(sso.warmUp.calls.isEmpty)
    },
    test("a warm-up that fails is dropped with a warning that names the client, and the start-up goes on") {
      val sso = stub[SSOClient]
      for
        _ <- sso.warmUp.failsWith(new RuntimeException("connection refused"))
        result <- SSOClientWarmUp.warmUp(sso, Seq(mutualTls("b-client"))).exit
        logs <- ZTestLogger.logOutput
        warnings = logs.filter(_.logLevel == LogLevel.Warning).map(_.message())
      yield assertTrue(
        result.isSuccess,
        sso.warmUp.calls.size == 1,
        warnings.size == 1,
        warnings.head.contains("b-client"),
      )
    },
    test("a warm-up that never answers is given up on after the timeout, with a warning") {
      val sso = stub[SSOClient]
      for
        _ <- sso.warmUp.returnsZIO(_ => ZIO.never)
        fiber <- SSOClientWarmUp.warmUp(sso, Seq(mutualTls("b-client"))).fork
        before <- fiber.poll
        _ <- TestClock.adjust(SSOClientWarmUp.Timeout + 1.second)
        result <- fiber.join.exit
        logs <- ZTestLogger.logOutput
      yield assertTrue(
        // still waiting until the timeout, not abandoned at once
        before.isEmpty,
        result.isSuccess,
        logs.count(_.logLevel == LogLevel.Warning) == 1,
      )
    },
    test("the layer returns at once, and warms in the background the client it found") {
      val sso = stub[SSOClient]
      val clientService = stub[OAuthClientService]
      val layer = (ZLayer.succeed[OAuthClientService](clientService) ++ ZLayer.succeed[SSOClient](sso)) >>> SSOClientWarmUp.live
      for
        started <- Promise.make[Nothing, Unit]
        // never completes: a layer that waited for it would never be built
        _ <- sso.warmUp.returnsZIO(_ => started.succeed(()) *> ZIO.never)
        _ <- clientService.listClients.succeedsWith(List(secretClient, mutualTls("b-client")))
        _ <- ZIO.scoped(layer.build *> started.await)
      yield assertTrue(sso.warmUp.calls.map(_.id) == List(ClientId("b-client")))
    } @@ TestAspect.timeout(10.seconds),
  )
