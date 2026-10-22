package versola.oauth.mtls

import versola.util.{EdgeIdentityNames, TestCertificates, UnitSpecBase}
import zio.*
import zio.test.*

import java.nio.file.Files

object EdgeCertificateTrustSpec extends UnitSpecBase:

  private val edgeIssuer = TestCertificates.generate(subject = "CN=edge-ca", ca = true)
  private val otherIssuer = TestCertificates.generate(subject = "CN=edge-ca", ca = true)
  private val trust = EdgeCertificateTrust.Impl(List(edgeIssuer.certificate))

  private def issuedBy(issuer: TestCertificates.Generated, uris: List[String]) =
    TestCertificates.generate(subject = "CN=edge-1", issuer = Some(issuer), uris = uris).certificate

  val spec = suite("EdgeCertificateTrust")(
    test("names the edge a certificate of the edge issuer carries") {
      val certificate = issuedBy(edgeIssuer, List(EdgeIdentityNames.edgeUri("edge-1"), EdgeIdentityNames.groupUri("default")))
      assertTrue(trust.edgeOf(certificate).contains("edge-1"))
    },
    test("does not take a certificate with no edge name for an edge's") {
      assertTrue(
        trust.edgeOf(issuedBy(edgeIssuer, Nil)).isEmpty,
        trust.edgeOf(issuedBy(edgeIssuer, List(EdgeIdentityNames.groupUri("default")))).isEmpty,
      )
    },
    test("does not take a name on a certificate some other issuer signed, even one with the edge issuer's own name") {
      // `otherIssuer` has the same subject DN as `edgeIssuer`: only the signature tells them apart.
      assertTrue(trust.edgeOf(issuedBy(otherIssuer, List(EdgeIdentityNames.edgeUri("edge-1")))).isEmpty)
    },
    test("does not take a certificate naming several edges, or an empty id") {
      assertTrue(
        trust.edgeOf(issuedBy(edgeIssuer, List(EdgeIdentityNames.edgeUri("edge-1"), EdgeIdentityNames.edgeUri("edge-2")))).isEmpty,
        trust.edgeOf(issuedBy(edgeIssuer, List(EdgeIdentityNames.edgeUri("")))).isEmpty,
      )
    },
    test("reads the issuers from a PEM file, refusing one with no certificate") {
      for
        good <- ZIO.attempt(Files.createTempFile("edge-trust", ".pem"))
        _ <- ZIO.attempt(Files.writeString(good, edgeIssuer.certificatePem))
        empty <- ZIO.attempt(Files.createTempFile("edge-trust-empty", ".pem"))
        loaded <- EdgeCertificateTrust.load(good.toString)
        refused <- EdgeCertificateTrust.load(empty.toString).either
      yield assertTrue(
        loaded.size == 1,
        refused.isLeft,
        EdgeCertificateTrust.Impl(loaded)
          .edgeOf(issuedBy(edgeIssuer, List(EdgeIdentityNames.edgeUri("edge-1")))).contains("edge-1"),
      )
    },
  )
