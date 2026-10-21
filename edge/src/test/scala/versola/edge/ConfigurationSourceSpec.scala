package versola.edge

import versola.edge.model.EdgeId
import versola.util.cel.CelEvaluator
import versola.util.{ConfigSnapshot, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.{Client, TestClient, URL}
import zio.test.*

import java.security.KeyPairGenerator

/** Boundary 1 of #555: the source a mode selects. Nothing is fetched here -- the sync clients
  * are built, not run -- so a test `Client` with no routes is enough.
  */
object ConfigurationSourceSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private def build(config: EdgeConfig): ZIO[Scope & Client, Throwable, ZEnvironment[ConfigurationSource]] =
    ConfigurationSource.live.build.provideSomeLayer[Scope & Client](
      ZLayer.succeed(config) ++ (SecureRandom.live >>> SecurityService.live) ++ CelEvaluator.live ++ ZLayer.succeed(ConfigSnapshot.disabled),
    )

  def spec = suite("ConfigurationSource")(
    test("first-party reads everything from central's sync endpoints") {
      for source <- build(config)
      yield assertTrue(
        source.get[AuthorizationPresetsSyncClient].isInstanceOf[AuthorizationPresetsSyncClient.Impl],
        source.get[OAuthClientsSyncClient].isInstanceOf[OAuthClientsSyncClient.Impl],
        source.get[ResourcesSyncClient].isInstanceOf[ResourcesSyncClient.Impl],
        source.get[RolesSyncClient].isInstanceOf[RolesSyncClient.Impl],
        source.get[PermissionsSyncClient].isInstanceOf[PermissionsSyncClient.Impl],
        source.get[DpopAlgorithmsSyncClient].isInstanceOf[DpopAlgorithmsSyncClient.Impl],
        source.get[DpopPolicySyncClient].isInstanceOf[DpopPolicySyncClient.Impl],
        source.get[JwksSyncClient].isInstanceOf[JwksSyncClient.Impl],
        source.get[OverloadPolicySyncClient].isInstanceOf[OverloadPolicySyncClient.Impl],
      )
    },
    test("partner is refused as not implemented, the same way the configuration is") {
      for error <- build(config.copy(mode = EdgeMode.Partner)).flip
      yield assertTrue(error == EdgeMode.NotImplemented(EdgeMode.Partner))
    },
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
