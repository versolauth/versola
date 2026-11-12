package versola.util.audit

import versola.util.{CertificateSubject, TestCertificates, UnitSpecBase}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.security.auth.x500.X500Principal

object CertAuditDetailsSpec extends UnitSpecBase:

  private val issued = TestCertificates.generate(subject = "CN=mobile-app,OU=tenant-a,O=Versola", dnsName = Some("mobile-app.clients.example.com"))

  def spec = suite("CertAuditDetails")(
    test("an issued certificate is described by its serial in hex, the end of its validity and its subject") {
      val json = CertAuditDetails.of("renew", Some("mobile-app"), issued.certificate).toJson
      assertTrue(
        json.get("operation") == Some(Json.Str("renew")),
        json.get("clientId") == Some(Json.Str("mobile-app")),
        json.get("serial") == Some(Json.Str(issued.certificate.getSerialNumber.toString(16))),
        json.get("notAfter") == Some(Json.Str(issued.certificate.getNotAfter.toInstant.toString)),
        json.get("subject") == Some(Json.Str("CN=mobile-app,OU=tenant-a,O=Versola")),
      )
    },
    test("an edge's certificate has no client, and the member is left out") {
      val json = CertAuditDetails.of("enroll", None, issued.certificate).toJson
      assertTrue(!json.fields.exists(_._1 == "clientId"))
    },
    test("a refused request has the subject it asked for and neither a serial nor a validity") {
      val csr = CertificateSubject.generate(
        CertificateSubject("CN=mobile-app,OU=tenant-a,O=Versola", "mobile-app", Nil, Nil, Nil, Nil),
      ).orDie
      for
        generated <- csr
        json = CertAuditDetails.refused("enroll", Some("mobile-app"), CertificateSubject.requestSubject(generated.csrPem)).toJson
      yield assertTrue(
        json.get("subject") == Some(Json.Str("CN=mobile-app,OU=tenant-a,O=Versola")),
        json.get("serial").isEmpty,
        json.get("notAfter").isEmpty,
      )
    },
    test("a request that cannot be read names no subject") {
      assertTrue(
        CertificateSubject.requestSubject("not a request").isEmpty,
        CertAuditDetails.refused("enroll", None, None).toJson.get("subject").isEmpty,
      )
    },
    test("a long subject is cut, so that a request nobody authenticated cannot make the event heavy") {
      val long = X500Principal("CN=" + "a" * 5000)
      val json = CertAuditDetails.refused("enroll", None, Some(long)).toJson
      assertTrue(json.get("subject").exists { case Json.Str(value) => value.length == 256; case _ => false })
    },
    test("the members are the closed set: there is no member a key, a PEM or a token could be put in") {
      val members = CertAuditDetails.of("sign", Some("mobile-app"), issued.certificate).toJson.fields.map(_._1).toSet
      assertTrue(
        members == Set("operation", "clientId", "serial", "notAfter", "subject"),
        !CertAuditDetails.of("sign", Some("mobile-app"), issued.certificate).toJson.toJson.contains("PRIVATE KEY"),
        !CertAuditDetails.of(
          "sign",
          Some("mobile-app"),
          issued.certificate,
        ).toJson.toJson.contains(issued.privateKeyPem.linesIterator.drop(1).next.take(40)),
      )
    },
  )
