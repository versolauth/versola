package versola.edge

import versola.edge.model.EdgeId
import versola.util.{EdgeIdentityNames, PrivateClientCertificate, Secret, TestCertificates}
import zio.*
import zio.http.URL
import zio.test.*

import java.security.KeyPairGenerator

/** Boundary 2 of #555: the identity a mode selects, and what the first-party one presents. */
object EdgeIdentitySpec extends ZIOSpecDefault:

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

  private val material = PrivateClientCertificate(
    TestCertificates.generate(subject = "CN=edge-1", uris = List(EdgeIdentityNames.edgeUri("edge-1"))).bundle,
  ).material.toOption.get

  private def holder(held: Option[PrivateClientCertificate.Material]): EdgeCertificateHolder =
    new EdgeCertificateHolder:
      override def paths = None
      override def material = ZIO.succeed(held)
      override def install(chain: String, privateKeyPem: String) = ZIO.unit

  def spec = suite("EdgeIdentity")(
    suite("Registered")(
      test("presents the certificate this edge holds for central") {
        for presented <- EdgeIdentity.Registered(holder(Some(material))).certificate
        yield assertTrue(presented == Some(material))
      },
      test("has none before the edge holds a certificate, and then the one central renewed it with") {
        for
          renewed <- Ref.make(Option.empty[PrivateClientCertificate.Material])
          current = new EdgeCertificateHolder:
            override def paths = None
            override def material = renewed.get
            override def install(chain: String, privateKeyPem: String) = ZIO.unit
          identity = EdgeIdentity.Registered(current)
          before <- identity.certificate
          _ <- renewed.set(Some(material))
          after <- identity.certificate
        yield assertTrue(before.isEmpty, after == Some(material))
      },
    ),
    suite("by mode")(
      test("first-party is the registered edge") {
        for
          identity <- ZIO.service[EdgeIdentity].provideLayer(
            ZLayer.succeed(config) ++ ZLayer.succeed(holder(None)) >>> EdgeIdentity.live,
          )
        yield assertTrue(identity.isInstanceOf[EdgeIdentity.Registered])
      },
      test("partner is refused as not implemented, the same way the configuration is") {
        for
          error <- ZIO.service[EdgeIdentity]
            .provideLayer(
              ZLayer.succeed(config.copy(mode = EdgeMode.Partner)) ++ ZLayer.succeed(holder(None)) >>> EdgeIdentity.live,
            )
            .flip
        yield assertTrue(error == EdgeMode.NotImplemented(EdgeMode.Partner))
      },
    ),
  )
