package versola.central.configuration.edges

import versola.util.TestCertificates
import zio.test.*

object EdgeCertificateSpec extends ZIOSpecDefault:
  private val edgeId = EdgeId("edge-a")

  def spec = suite("EdgeCertificate")(
    test("a certificate with the edge's URI name is issued to it") {
      assertTrue(EdgeCertificate.isIssuedTo(TestCertificates.generate(uri = Some("urn:versola:edge:edge-a")).certificate, edgeId))
    },
    test("the name is built the way the check reads it") {
      assertTrue(EdgeCertificate.uriFor(edgeId) == "urn:versola:edge:edge-a")
    },
    test("a certificate for another edge is not") {
      assertTrue(!EdgeCertificate.isIssuedTo(TestCertificates.generate(uri = Some("urn:versola:edge:edge-b")).certificate, edgeId))
    },
    test("a certificate naming several edges is refused for all of them") {
      val both = TestCertificates.generate(uris = List("urn:versola:edge:edge-a", "urn:versola:edge:edge-b")).certificate
      assertTrue(!EdgeCertificate.isIssuedTo(both, edgeId), !EdgeCertificate.isIssuedTo(both, EdgeId("edge-b")))
    },
    test("a certificate whose subject CN says edge-a but carries no URI name is refused") {
      assertTrue(!EdgeCertificate.isIssuedTo(TestCertificates.generate(subject = "CN=edge-a,O=Versola").certificate, edgeId))
    },
    test("a DNS name that looks like the URI is not a URI name") {
      assertTrue(!EdgeCertificate.isIssuedTo(TestCertificates.generate(dnsName = Some("urn:versola:edge:edge-a")).certificate, edgeId))
    },
    test("a prefix match is not a match") {
      assertTrue(!EdgeCertificate.isIssuedTo(TestCertificates.generate(uri = Some("urn:versola:edge:edge-a-and-more")).certificate, edgeId))
    },
    test("a group name is not an edge name, and the other way round") {
      val certificate = TestCertificates.generate(uris = List("urn:versola:edge:edge-a", "urn:versola:edge-group:default")).certificate
      assertTrue(
        EdgeCertificate.edgeIds(certificate) == Set("edge-a"),
        EdgeCertificate.groupIds(certificate) == Set("default"),
        EdgeCertificate.groupUriFor(EdgeGroupId("default")) == "urn:versola:edge-group:default",
      )
    },
    test("a certificate that names the edge and its own group is issued to it") {
      val certificate = TestCertificates.generate(uris = List("urn:versola:edge:edge-a", "urn:versola:edge-group:default")).certificate
      assertTrue(EdgeCertificate.isIssuedTo(certificate, edgeId, EdgeGroupId("default")))
    },
    test("a group name other than the edge's own is refused") {
      val certificate = TestCertificates.generate(uris = List("urn:versola:edge:edge-a", "urn:versola:edge-group:partner")).certificate
      assertTrue(!EdgeCertificate.isIssuedTo(certificate, edgeId, EdgeGroupId("default")))
    },
    test("a certificate with no group name still passes, the way those issued before groups do") {
      val certificate = TestCertificates.generate(uri = Some("urn:versola:edge:edge-a")).certificate
      assertTrue(EdgeCertificate.isIssuedTo(certificate, edgeId, EdgeGroupId("default")))
    },
    test("edge and group names are reserved: a client is never issued one") {
      assertTrue(
        EdgeCertificate.isReserved("urn:versola:edge:edge-a"),
        EdgeCertificate.isReserved("urn:versola:edge-group:default"),
        !EdgeCertificate.isReserved("urn:example:client"),
      )
    },
  )
