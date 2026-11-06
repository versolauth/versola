package versola.oauth.revocation

import org.scalamock.stubs.Stub
import versola.oauth.client.model.TenantId
import versola.oauth.mtls.EdgeCertificateTrust
import versola.oauth.revocation.RevocationFeedController.Page
import versola.util.http.{NoopTracing, Observability}
import versola.util.revocation.{Revocation, RevocationCursor, RevocationKey, RevocationPage}
import versola.util.{EdgeIdentityNames, TestCertificates, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import java.security.cert.X509Certificate
import java.time.Instant

object RevocationFeedControllerSpec extends UnitSpecBase:

  val tenantA = TenantId("tenant-a")
  val tenantB = TenantId("tenant-b")

  val edgeIssuer = TestCertificates.generate(subject = "CN=edge-ca", ca = true)
  val edgeCertificate: X509Certificate = TestCertificates.generate(
    subject = "CN=edge-1",
    issuer = Some(edgeIssuer),
    uris = List(EdgeIdentityNames.edgeUri("edge-1")),
  ).certificate

  /** A client's issuer, trusted by the listener for clients: whatever name it signs into a certificate, it is not the
    * edge issuer. */
  val clientIssuer = TestCertificates.generate(subject = "CN=client-ca", ca = true)
  val forgedEdgeName: X509Certificate = TestCertificates.generate(
    subject = "CN=edge-1",
    issuer = Some(clientIssuer),
    uris = List(EdgeIdentityNames.edgeUri("edge-1")),
  ).certificate
  val plainClient: X509Certificate = TestCertificates.generate().certificate

  /** edge-1 is assigned to tenant A only. */
  val trust = EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), ZIO.succeed(Map("edge-1" -> Set(tenantA))))
  val unlistedEdge = EdgeCertificateTrust.Impl(List(edgeIssuer.certificate), ZIO.succeed(Map.empty))

  val expiry = Instant.ofEpochSecond(2_000_000_000L)
  val sidRevocation = Revocation(RevocationKey.Sid("session-1"), expiry, None)
  val subRevocation = Revocation(RevocationKey.Sub("user-1"), expiry, Some(Instant.ofEpochSecond(1_999_999_000L)))
  val tokenRevocation = Revocation(RevocationKey.Jti("token-1"), expiry, None)
  val lastRowAt = RevocationCursor(Instant.parse("2026-11-06T10:00:00.123456Z"), "sub:user-1")

  private def get(query: String, certificate: Option[X509Certificate]) =
    Request.get(URL.decode(s"/revocations$query").toOption.get).copy(remoteCertificate = certificate)

  private def dispatch(
      request: Request,
      edgeTrust: EdgeCertificateTrust = trust,
      page: RevocationPage = RevocationPage(Nil, None, hasMore = false),
  ): ZIO[Scope, Throwable, (Response, Stub[AuthRevocationRepository])] =
    for
      tracing <- NoopTracing.layer.build
      repository = stub[AuthRevocationRepository]
      _ <- repository.feedSince.succeedsWith(page)
      routes = Observability.handleErrors(
        RevocationFeedController.routes.provideEnvironment(
          ZEnvironment(edgeTrust) ++ ZEnvironment[AuthRevocationRepository](repository) ++ tracing,
        ),
      )
      response <- routes.runZIO(request)
    yield (response, repository)

  val spec = suite("RevocationFeedController")(
    test("serves a page of an edge's tenants to the edge whose certificate the handshake validated") {
      for
        (response, repository) <- dispatch(
          get("", Some(edgeCertificate)),
          page = RevocationPage(List(tokenRevocation, sidRevocation, subRevocation), Some(lastRowAt), hasMore = false),
        )
        body <- response.body.asString
        page <- ZIO.fromEither(body.fromJson[Page]).mapError(new RuntimeException(_))
      yield assertTrue(
        response.status == Status.Ok,
        page.revocations.map(_.key) == List("jti:token-1", "sid:session-1", "sub:user-1"),
        page.revocations.map(_.exp).distinct == List(2_000_000_000L),
        page.revocations.map(_.before) == List(None, None, Some(1_999_999_000L)),
        repository.feedSince.calls == List((RevocationCursor.Beginning, RevocationFeedController.DefaultLimit, Set(tenantA))),
      )
    },
    test("asks for the calling edge's tenants only, whatever the request says") {
      for
        (_, repository) <- dispatch(get("?tenant=tenant-b", Some(edgeCertificate)))
      yield assertTrue(repository.feedSince.calls.map(_._3) == List(Set(tenantA)))
    },
    test("refuse a client's certificate with 403 and read nothing") {
      for
        (response, repository) <- dispatch(get("", Some(plainClient)))
      yield assertTrue(response.status == Status.Forbidden, repository.feedSince.calls.isEmpty)
    },
    test("refuse an edge name signed by an issuer that is not the edge issuer") {
      for
        (response, repository) <- dispatch(get("", Some(forgedEdgeName)))
      yield assertTrue(response.status == Status.Forbidden, repository.feedSince.calls.isEmpty)
    },
    test("refuse an edge certificate of an edge central no longer lists") {
      for
        (response, repository) <- dispatch(get("", Some(edgeCertificate)), edgeTrust = unlistedEdge)
      yield assertTrue(response.status == Status.Forbidden, repository.feedSince.calls.isEmpty)
    },
    test("refuse a request that carried no certificate, whatever its headers claim") {
      for
        (response, repository) <- dispatch(
          get("", None).addHeader(Header.Custom("X-Client-Cert", "anything")).addHeader(Header.Custom("X-Forwarded-Client-Cert", "anything")),
        )
      yield assertTrue(response.status == Status.Forbidden, repository.feedSince.calls.isEmpty)
    },
    test("resume after the cursor it handed out, and hand out the position of the last row") {
      for
        (first, _) <- dispatch(get("", Some(edgeCertificate)), page = RevocationPage(List(subRevocation), Some(lastRowAt), hasMore = false))
        cursor <- first.body.asString.map(_.fromJson[Page].toOption.get.cursor)
        (_, repository) <- dispatch(get(s"?after=$cursor", Some(edgeCertificate)))
      yield assertTrue(cursor.nonEmpty, repository.feedSince.calls.map(_._1) == List(lastRowAt))
    },
    test("an empty page hands back the cursor it was given") {
      for
        (first, _) <- dispatch(get("", Some(edgeCertificate)), page = RevocationPage(List(subRevocation), Some(lastRowAt), hasMore = false))
        cursor <- first.body.asString.map(_.fromJson[Page].toOption.get.cursor)
        (second, _) <- dispatch(get(s"?after=$cursor", Some(edgeCertificate)))
        page <- second.body.asString.map(_.fromJson[Page].toOption.get)
      yield assertTrue(second.status == Status.Ok, page.revocations.isEmpty, page.cursor == cursor)
    },
    test("an empty page from the beginning stays at the beginning") {
      for
        (response, _) <- dispatch(get("", Some(edgeCertificate)))
        page <- response.body.asString.map(_.fromJson[Page].toOption.get)
      yield assertTrue(page.revocations.isEmpty, page.cursor == "")
    },
    test("cap the limit at the maximum page") {
      for
        (_, repository) <- dispatch(get("?limit=1000000", Some(edgeCertificate)))
      yield assertTrue(repository.feedSince.calls.map(_._2) == List(RevocationFeedController.MaxLimit))
    },
    test("serve the limit asked for when it is within the maximum") {
      for
        (_, repository) <- dispatch(get("?limit=7", Some(edgeCertificate)))
      yield assertTrue(repository.feedSince.calls.map(_._2) == List(7))
    },
    test("refuse a limit that is not a positive integer") {
      for
        (zero, repositoryZero) <- dispatch(get("?limit=0", Some(edgeCertificate)))
        (text, repositoryText) <- dispatch(get("?limit=many", Some(edgeCertificate)))
      yield assertTrue(
        zero.status == Status.BadRequest,
        text.status == Status.BadRequest,
        repositoryZero.feedSince.calls.isEmpty,
        repositoryText.feedSince.calls.isEmpty,
      )
    },
    test("refuse a cursor it did not issue") {
      for
        (response, repository) <- dispatch(get("?after=not-a-cursor", Some(edgeCertificate)))
      yield assertTrue(response.status == Status.BadRequest, repository.feedSince.calls.isEmpty)
    },
  ) @@ TestAspect.withLiveClock // certificate validity is judged against the clock
