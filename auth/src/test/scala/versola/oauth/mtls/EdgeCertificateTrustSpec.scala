package versola.oauth.mtls

import versola.oauth.client.model.TenantId
import versola.util.{EdgeIdentityNames, TestCertificates, UnitSpecBase}
import zio.*
import zio.test.*

import java.nio.file.Files
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

object EdgeCertificateTrustSpec extends UnitSpecBase:

  private val tenantA = TenantId("tenant-a")
  private val edgeIssuer = TestCertificates.generate(subject = "CN=edge-ca", ca = true)
  private val otherIssuer = TestCertificates.generate(subject = "CN=edge-ca", ca = true)
  private val known = Map("edge-1" -> Set(tenantA))
  private val trust = EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), ZIO.succeed(known))

  private def issuedBy(
      issuer: TestCertificates.Generated,
      uris: List[String],
      extendedKeyUsage: Option[List[String]] = None,
  ): X509Certificate =
    TestCertificates.generate(subject = "CN=edge-1", issuer = Some(issuer), uris = uris, extendedKeyUsage = extendedKeyUsage).certificate

  private val edgeOne = List(EdgeIdentityNames.edgeUri("edge-1"), EdgeIdentityNames.groupUri("default"))

  private def writePem(content: String) =
    ZIO.attempt:
      val file = Files.createTempFile("edge-trust", ".pem")
      Files.writeString(file, content)
      file.toString

  val spec = suite("EdgeCertificateTrust")(
    test("names the edge a certificate of the edge issuer carries, with the tenants central assigned it") {
      for edge <- trust.edgeOf(issuedBy(edgeIssuer, edgeOne))
      yield assertTrue(edge == Some(EdgeCertificateTrust.Edge("edge-1", Set(tenantA))))
    },
    test("does not take a certificate with no edge name for an edge's") {
      for
        none <- trust.edgeOf(issuedBy(edgeIssuer, Nil))
        group <- trust.edgeOf(issuedBy(edgeIssuer, List(EdgeIdentityNames.groupUri("default"))))
      yield assertTrue(none.isEmpty, group.isEmpty)
    },
    test("does not take a name on a certificate some other issuer signed, even one with the edge issuer's own name") {
      // `otherIssuer` has the same subject DN as `edgeIssuer`: only the signature tells them apart.
      for edge <- trust.edgeOf(issuedBy(otherIssuer, List(EdgeIdentityNames.edgeUri("edge-1"))))
      yield assertTrue(edge.isEmpty)
    },
    test("does not take a certificate naming several edges, or an empty id") {
      for
        several <- trust.edgeOf(issuedBy(edgeIssuer, List(EdgeIdentityNames.edgeUri("edge-1"), EdgeIdentityNames.edgeUri("edge-2"))))
        empty <- trust.edgeOf(issuedBy(edgeIssuer, List(EdgeIdentityNames.edgeUri(""))))
      yield assertTrue(several.isEmpty, empty.isEmpty)
    },
    // Deleting the edge in central is what cuts a certificate off before it expires.
    test("an edge central no longer lists is a client, whatever its certificate says") {
      val deleted = EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), ZIO.succeed(Map("edge-2" -> Set(tenantA))))
      for
        edge <- deleted.edgeOf(issuedBy(edgeIssuer, edgeOne))
        empty <- EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), ZIO.succeed(Map.empty)).edgeOf(issuedBy(edgeIssuer, edgeOne))
      yield assertTrue(edge.isEmpty, empty.isEmpty)
    },
    // Central unreachable at startup with no snapshot leaves the cache empty: nothing is vouched for.
    test("with an empty registry (central unreachable) an edge certificate is a client's, not failing open") {
      for edge <- EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), ZIO.succeed(Map.empty)).edgeOf(issuedBy(edgeIssuer, edgeOne))
      yield assertTrue(edge.isEmpty)
    },
    test("follows the registry as it changes") {
      for
        registry <- Ref.make(Map.empty[String, Set[TenantId]])
        live = EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), registry.get)
        certificate = issuedBy(edgeIssuer, edgeOne)
        before <- live.edgeOf(certificate)
        _ <- registry.set(known)
        added <- live.edgeOf(certificate)
        _ <- registry.set(Map.empty)
        removed <- live.edgeOf(certificate)
      yield assertTrue(before.isEmpty, added.isDefined, removed.isEmpty)
    },
    test("refresh reloads the registry without waiting for the next interval") {
      for
        registry <- Ref.make(Map.empty[String, Set[TenantId]])
        live = EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), registry.get, registry.set(known))
        certificate = issuedBy(edgeIssuer, edgeOne)
        before <- live.edgeOf(certificate)
        _ <- live.refresh
        after <- live.edgeOf(certificate)
      yield assertTrue(before.isEmpty, after.isDefined)
    },
    test("does not take a certificate whose issuer is not valid now") {
      val expired = TestCertificates.generate(
        subject = "CN=expired-edge-ca",
        ca = true,
        validity = Some((Date.from(Instant.now.minusSeconds(2 * 86_400L)), Date.from(Instant.now.minusSeconds(86_400L)))),
      )
      val onExpired = EdgeCertificateTrust.Impl(List(expired.certificate), ZIO.succeed(known))
      for edge <- onExpired.edgeOf(issuedBy(expired, edgeOne))
      yield assertTrue(edge.isEmpty)
    },
    test("does not take an authority's certificate carrying an edge name") {
      val asIssuer = TestCertificates.generate(subject = "CN=edge-1", issuer = Some(edgeIssuer), ca = true, uris = edgeOne).certificate
      for edge <- trust.edgeOf(asIssuer)
      yield assertTrue(edge.isEmpty)
    },
    test("takes a certificate for client authentication, and refuses one whose extended key usage excludes it") {
      for
        clientAuth <- trust.edgeOf(issuedBy(edgeIssuer, edgeOne, Some(List("1.3.6.1.5.5.7.3.2"))))
        serverOnly <- trust.edgeOf(issuedBy(edgeIssuer, edgeOne, Some(List("1.3.6.1.5.5.7.3.1"))))
      yield assertTrue(clientAuth.isDefined, serverOnly.isEmpty)
    },
    suite("loading the issuers")(
      test("reads authorities from a PEM file") {
        for
          path <- writePem(edgeIssuer.certificatePem)
          loaded <- EdgeCertificateTrust.load(path)
        yield assertTrue(loaded.size == 1)
      },
      test("refuses a file with no certificate") {
        for
          path <- writePem("")
          refused <- EdgeCertificateTrust.load(path).either
        yield assertTrue(refused.isLeft)
      },
      test("refuses a leaf listed by mistake, so a client's certificate cannot act as an edge issuer") {
        for
          path <- writePem(TestCertificates.generate(subject = "CN=a-client").certificatePem)
          refused <- EdgeCertificateTrust.load(path).either
        yield assertTrue(refused.isLeft)
      },
      test("refuses an authority that may not sign certificates, and takes one that may") {
        // KeyUsage.digitalSignature = 128; keyCertSign = 4 (BouncyCastle's bit constants).
        for
          noSigning <- writePem(TestCertificates.generate(subject = "CN=ca", ca = true, keyUsage = Some(128)).certificatePem)
          signing <- writePem(TestCertificates.generate(subject = "CN=ca", ca = true, keyUsage = Some(4)).certificatePem)
          refused <- EdgeCertificateTrust.load(noSigning).either
          accepted <- EdgeCertificateTrust.load(signing).either
        yield assertTrue(refused.isLeft, accepted.isRight)
      },
    ),
  ) @@ TestAspect.withLiveClock // certificates are valid in real time, not at the test clock's epoch
