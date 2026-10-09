package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import versola.util.Dpop
import zio.http.*
import zio.test.*
import zio.{Ref, UIO, ZIO}

import java.net.URLDecoder

/** The native flow of #420 as the driver puts it on the wire, against a stub of edge's
  * native endpoints and auth's `/authorize`.
  *
  * Every proof is checked with `Dpop.verify` against the `htu` the SUT would hold it to: edge's
  * own URI for `start`, auth's mutual-TLS alias for everything forwarded.
  */
object NativeAuthClientSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://mock.test", StubSut.origin)
  private val clientId = StubSut.publicClient.creds.clientId
  private val mtlsToken = "https://mtls.auth.test/token"
  private val startHtu = StubSut.edgeUrl + "/native/start/" + clientId
  private val edgeNonce = "edge-nonce-1"
  private val authNonce = "auth-nonce-1"
  private val leeway = zio.Duration.fromSeconds(30)

  private val startBody =
    s"""{"client_id":"$clientId","request_uri":"urn:ietf:params:oauth:request_uri:r1","expires_in":60,""" +
      s""""authorization_endpoint":"${StubSut.authUrl}/authorize","state":"st-1","blob":"blob-1",""" +
      s""""token_endpoint":"$mtlsToken","revocation_endpoint":"https://mtls.auth.test/revoke"}"""

  private val tokens = """{"token_type":"DPoP","access_token":"at-1","refresh_token":"rt-2","expires_in":900}"""

  private val metadata = s"""{"issuer":"${StubSut.authUrl}","mtls_endpoint_aliases":{"token_endpoint":"$mtlsToken"}}"""

  private def form(body: String): Map[String, String] =
    body.split("&").toList.filter(_.nonEmpty).map: pair =>
      val Array(name, value) = pair.split("=", 2).padTo(2, "")
      URLDecoder.decode(name, "UTF-8") -> URLDecoder.decode(value, "UTF-8")
    .toMap

  /** What the stub saw: path, `DPoP` header, form. */
  private final case class Seen(path: String, proof: Option[String], form: Map[String, String], query: Map[String, String])

  private final class Stub(
      seen: Ref[Vector[Seen]],
      startChallenges: Ref[Int],
      tokenReplies: Ref[List[Response]],
  ):
    def calls: UIO[Vector[Seen]] = seen.get

    private def record(request: Request): UIO[Unit] =
      request.body.asString.orDie.flatMap: body =>
        seen.update(_ :+ Seen(
          request.url.path.toString,
          request.rawHeader("DPoP"),
          form(body),
          request.url.queryParams.map.map((k, v) => k -> v.headOption.getOrElse("")),
        ))

    private def nonceChallenge(status: Status, nonce: String): Response =
      Response.json("""{"error":"use_dpop_nonce"}""").status(status)
        .addHeader(Header.Custom("DPoP-Nonce", nonce))
        .addHeader(Header.Custom("WWW-Authenticate", """DPoP error="use_dpop_nonce""""))

    private def proofCarries(request: Request, nonce: String): Boolean =
      request.rawHeader("DPoP").exists: proof =>
        String(java.util.Base64.getUrlDecoder.decode(proof.split('.')(1)), "UTF-8").contains(s""""nonce":"$nonce"""")

    def routes: Routes[Any, Nothing] =
      Routes(
        Method.POST / "native" / "start" / string("client") -> handler: (client: String, request: Request) =>
          record(request) *> startChallenges.get.flatMap: remaining =>
            // Edge's own nonce: refused until a proof carries it.
            if remaining > 0 && !proofCarries(request, edgeNonce) then
              startChallenges.update(_ - 1).as(nonceChallenge(Status.Unauthorized, edgeNonce))
            else ZIO.succeed(Response.json(startBody)),
        Method.GET / "authorize" -> handler: (request: Request) =>
          record(request).as(
            Response.status(Status.SeeOther)
              .addHeader(Header.Location(URL.decode("/challenge").toOption.get))
              .addCookie(Cookie.Response("SSO_CONVERSATION", StubSut.conversation)),
          ),
        Method.POST / "native" / string("endpoint") / string("client") -> handler: (endpoint: String, client: String, request: Request) =>
          record(request) *> tokenReplies.modify:
            case head :: tail => (head, tail)
            case Nil =>
              if proofCarries(request, authNonce) then (Response.json(tokens), Nil)
              else (nonceChallenge(Status.BadRequest, authNonce), Nil),
        Method.GET / ".well-known" / "openid-configuration" -> handler: (request: Request) =>
          record(request).as(Response.json(metadata)),
      )

  private def stub(startChallenges: Int, replies: Response*): UIO[Stub] =
    for
      seen <- Ref.make(Vector.empty[Seen])
      challenges <- Ref.make(startChallenges)
      queued <- Ref.make(replies.toList)
    yield Stub(seen, challenges, queued)

  private def authFor(sut: Stub): ZIO[TestClient & Client, ProtocolError, AuthClient] =
    for
      _ <- TestClient.addRoutes(sut.routes)
      client <- ZIO.service[Client]
      http <- HttpAuthClient.make(client, targets, StubSut.registry, StubSut.requestTimeout)
      auth <- NativeAuthClient.make(client, targets, StubSut.registry, http, StubSut.requestTimeout)
    yield auth

  private def verify(proof: String, uri: String) =
    zio.Clock.instant.flatMap: now =>
      Dpop.verify(proof, Dpop.KeyPolicy(Dpop.Algorithm.Default, Dpop.KeyPolicy.MinRsaKeySize), Method.POST, uri, now, leeway)
        .mapError(error => RuntimeException(error.toString))

  private def key = DpopKeyPool.derive("native-spec", 1).map(_.keyFor(0L))

  def spec = suite("NativeAuthClient")(
    test("start, authorize, complete: each hop carries what edge and auth hold it to") {
      for
        sut <- stub(startChallenges = 1)
        auth <- authFor(sut)
        signing <- key
        outcome <- auth.authorize("openid offline_access", None, Some(List("urn:acr:otp")), None, Some(signing))
        started <- outcome match
          case AuthorizeOutcome.Started(started) => ZIO.succeed(started)
          case other => ZIO.fail(RuntimeException(s"expected a conversation, got $other"))
        tokens <- auth.exchangeCode(AuthCode("code-1"), started.codeVerifier, StubSut.publicClient.creds, Some(signing))
        calls <- sut.calls
        startCalls = calls.filter(_.path == "/native/start/" + clientId)
        authorize = calls.find(_.path == "/authorize")
        complete = calls.find(_.path == "/native/complete/" + clientId)
        startProof <- verify(startCalls.last.proof.get, startHtu)
        completeProofs = calls.filter(_.path == "/native/complete/" + clientId).flatMap(_.proof)
        completeProof <- verify(completeProofs.last, mtlsToken)
      yield assertTrue(
        started.state == "st-1",
        tokens.accessToken == AccessToken("at-1"),
        tokens.refreshToken.contains(RefreshToken("rt-2")),
      ) &&
        assertTrue(startCalls.size == 2, startCalls.head.form("scope") == "openid offline_access", startCalls.head.form("acr_values") == "urn:acr:otp")
          .label("edge's nonce challenge is answered once, with a proof carrying it") &&
        assertTrue(startProof.jkt == signing.jkt, completeProof.jkt == signing.jkt) &&
        assertTrue(authorize.exists(_.query == Map("client_id" -> clientId, "request_uri" -> "urn:ietf:params:oauth:request_uri:r1")))
          .label("the authorize hop is the pushed one: client_id and request_uri only") &&
        assertTrue(
          complete.exists(_.form == Map("code" -> "code-1", "state" -> "st-1", "iss" -> StubSut.authUrl, "blob" -> "blob-1")),
          completeProofs.size == 2,
        ).label("auth's nonce, relayed as a 400, is answered once")
    },
    test("a refresh with nothing started reads auth's mTLS alias off its metadata and signs for that") {
      for
        sut <- stub(startChallenges = 0)
        auth <- authFor(sut)
        signing <- key
        refreshed <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing))
        calls <- sut.calls
        refresh = calls.filter(_.path == "/native/token/" + clientId)
        proof <- verify(refresh.last.proof.get, mtlsToken)
      yield assertTrue(
        refreshed.refreshToken.contains(RefreshToken("rt-2")),
        refresh.head.form == Map("refresh_token" -> "rt-1"),
        proof.jkt == signing.jkt,
        calls.count(_.path == "/.well-known/openid-configuration") == 1,
      )
    },
    test("a native flow without a device key is a configuration fault, not a bearer fallback") {
      for
        sut <- stub(startChallenges = 0)
        auth <- authFor(sut)
        failure <- auth.authorize("openid", None, None, None, None).either
        calls <- sut.calls
      yield assertTrue(
        failure.left.exists(_.isInstanceOf[ProtocolError.Misconfigured]),
        calls.isEmpty,
      )
    },
    test("a refresh auth turns down is a rejected refresh; a refused proof is the emulator's fault") {
      for
        sut <- stub(
          startChallenges = 0,
          Response.json("""{"error":"invalid_grant"}""").status(Status.BadRequest),
          Response.json("""{"error":"invalid_dpop_proof"}""").status(Status.BadRequest),
        )
        auth <- authFor(sut)
        signing <- key
        grant <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing)).either
        proof <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing)).either
      yield assertTrue(
        grant.left.exists(_.isInstanceOf[ProtocolError.RefreshRejected]),
        proof.left.exists(_.isInstanceOf[ProtocolError.Misconfigured]),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.sequential
