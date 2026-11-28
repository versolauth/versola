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
      val json = CertAuditDetails.of(CertOperation.Renew, Some("mobile-app"), issued.certificate).toJson
      assertTrue(
        json.get("operation") == Some(Json.Str("renew")),
        json.get("clientId") == Some(Json.Str("mobile-app")),
        json.get("serial") == Some(Json.Str(issued.certificate.getSerialNumber.toString(16))),
        json.get("notAfter") == Some(Json.Str(issued.certificate.getNotAfter.toInstant.toString)),
        json.get("subject") == Some(Json.Str("CN=mobile-app,OU=tenant-a,O=Versola")),
      )
    },
    test("an edge's certificate has no client, and the member is left out") {
      val json = CertAuditDetails.of(CertOperation.Enroll, None, issued.certificate).toJson
      assertTrue(!json.fields.exists(_._1 == "clientId"))
    },
    test("a refused request has the subject it asked for and neither a serial nor a validity") {
      val csr = CertificateSubject.generate(
        CertificateSubject("CN=mobile-app,OU=tenant-a,O=Versola", "mobile-app", Nil, Nil, Nil, Nil),
      ).orDie
      for
        generated <- csr
        json = CertAuditDetails.refused(CertOperation.Enroll, Some("mobile-app"), CertificateSubject.requestSubject(generated.csrPem)).toJson
      yield assertTrue(
        json.get("subject") == Some(Json.Str("CN=mobile-app,OU=tenant-a,O=Versola")),
        json.get("serial").isEmpty,
        json.get("notAfter").isEmpty,
      )
    },
    test("a request that cannot be read names no subject") {
      assertTrue(
        CertificateSubject.requestSubject("not a request").isEmpty,
        CertAuditDetails.refused(CertOperation.Enroll, None, None).toJson.get("subject").isEmpty,
      )
    },
    test("a long subject is cut, so that a request nobody authenticated cannot make the event heavy") {
      val long = X500Principal("CN=" + "a" * 5000)
      val json = CertAuditDetails.refused(CertOperation.Enroll, None, Some(long)).toJson
      assertTrue(json.get("subject").exists { case Json.Str(value) => value.length == 256; case _ => false })
    },
    test("a subject is not cut inside an escape: one that ends in a backslash at the cut is cut before it") {
      // `CN=` and 252 letters are 255 characters, so the backslash of `\,` is the 256th and its comma the 257th
      val principal = X500Principal("CN=" + "a" * 252 + "\\,bbb")
      val written = principal.getName(X500Principal.RFC2253)
      val subject = subjectOf(CertAuditDetails.refused(CertOperation.Enroll, None, Some(principal)))
      assertTrue(
        written.charAt(255) == '\\',
        written.charAt(256) == ',',
        subject.length == 255,
        !subject.endsWith("\\"),
        written.startsWith(subject),
      )
    },
    test("a name is not cut inside the two-digit escape of a byte either") {
      // the writer escapes a byte as `\XX`; the first of the two digits is the 256th character
      val name = "CN=" + "a" * 252 + "\\C3\\A9" + "bbb"
      val cut = CertAuditDetails.wholeEnd(name)
      assertTrue(name.substring(255, 258) == "\\C3", cut.length == 255, cut == name.take(255))
    },
    test("a name is cut after an escape that ends exactly at the limit, and whole when it fits") {
      val atLimit = "CN=" + "a" * 251 + "\\,bbb"
      val fits = "CN=" + "a" * 100 + "\\C3\\A9"
      assertTrue(CertAuditDetails.wholeEnd(atLimit) == atLimit.take(256), CertAuditDetails.wholeEnd(fits) == fits)
    },
    test("a subject is not cut between the halves of a surrogate pair") {
      // `CN=` and 252 letters are 255 characters, so the emoji (two chars) would be cut after its first half
      val principal = X500Principal("CN=" + "a" * 252 + "\uD83D\uDE00" + "bbb")
      val subject = subjectOf(CertAuditDetails.refused(CertOperation.Enroll, None, Some(principal)))
      assertTrue(
        subject.length == 255,
        !Character.isHighSurrogate(subject.last),
        subject.forall(c => !Character.isSurrogate(c)),
      )
    },
    test("a subject that fits is written whole, escapes and emoji included") {
      val principal = X500Principal("CN=a\\,b \uD83D\uDE00,O=Versola")
      val subject = subjectOf(CertAuditDetails.refused(CertOperation.Enroll, None, Some(principal)))
      assertTrue(subject == principal.getName(X500Principal.RFC2253))
    },
    test("the operation is one of a closed set, written as the same names in auth and central") {
      assertTrue(
        CertOperation.values.toList.map(_.name) == List("enroll", "renew", "sign", "issue", "revoke_renewal"),
        CertOperation.values.forall(operation =>
          CertAuditDetails.refused(operation, None, None).toJson.get("operation") == Some(Json.Str(operation.name)),
        ),
      )
    },
    test("the members are the closed set: there is no member a key, a PEM or a token could be put in") {
      val stored = CertAuditDetails.of(CertOperation.Sign, Some("mobile-app"), issued.certificate).toJson
      val body = issued.privateKeyPem.linesIterator.filterNot(_.startsWith("-----")).mkString
      val text = stored.toJson
      assertTrue(
        stored.fields.map(_._1).toSet == Set("operation", "clientId", "serial", "notAfter", "subject"),
        !text.contains("PRIVATE KEY"),
        // every 40-character window of the key, not its first characters
        body.nonEmpty,
        !body.sliding(40).exists(text.contains),
      )
    },
  )

  private def subjectOf(details: CertAuditDetails): String =
    details.toJson.get("subject").collect { case Json.Str(value) => value }.getOrElse("")
