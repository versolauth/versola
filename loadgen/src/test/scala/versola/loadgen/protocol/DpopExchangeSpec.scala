package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import versola.util.Dpop
import zio.http.*
import zio.test.*
import zio.{Ref, UIO, ZIO}

/** What the driver actually puts on the wire in DPoP mode, and how it reads what comes back.
  *
  * Against a stub token endpoint rather than [[StubSut]]'s, because RFC 9449 §9's handshake is a
  * property of a *sequence* of calls -- a challenge, then a retry carrying what the challenge
  * issued -- and the shared fixture answers each request the same way.
  *
  * Every proof this spec captures is checked with `Dpop.verify`, the SUT's own code, so a change
  * that kept the header's shape while breaking its contents cannot pass.
  */
object DpopExchangeSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://central.test", "http://mock.test", StubSut.origin)
  private val tokenHtu = StubSut.authUrl + "/token"
  private val leeway = zio.Duration.fromSeconds(30)
  private val issuedNonce = "nonce-from-auth"

  private val tokens =
    """{"token_type":"DPoP","access_token":"at-1","refresh_token":"rt-2","expires_in":900}"""

  /** Records every `DPoP` header the endpoint was sent, and answers with whatever the test set up. */
  private final case class Endpoint(proofs: Ref[Vector[String]], replies: Ref[List[Response]]):
    def routes: Routes[Any, Nothing] =
      Routes(
        Method.POST / "token" -> handler: (request: Request) =>
          for
            _ <- proofs.update(_ ++ request.rawHeader("DPoP").toVector)
            next <- replies.modify:
              case head :: tail => (head, tail)
              case Nil => (Response.json(tokens), Nil)
          yield next,
      )

    def captured: UIO[Vector[String]] = proofs.get

  private def endpointAnswering(replies: Response*): UIO[Endpoint] =
    for
      proofs <- Ref.make(Vector.empty[String])
      queued <- Ref.make(replies.toList)
    yield Endpoint(proofs, queued)

  private def authFor(endpoint: Endpoint): ZIO[TestClient & Client, ProtocolError, AuthClient] =
    for
      _ <- TestClient.addRoutes(endpoint.routes)
      client <- ZIO.service[Client]
      auth <- HttpAuthClient.make(client, targets, StubSut.registry, StubSut.requestTimeout)
    yield auth

  private def errorResponse(code: String, nonce: Option[String] = None): Response =
    val response = Response.json(s"""{"error":"$code"}""").status(Status.BadRequest)
    nonce.fold(response)(value => response.addHeader(Header.Custom("DPoP-Nonce", value)))

  private def verify(proof: String, method: Method = Method.POST, uri: String = tokenHtu) =
    zio.Clock.instant.flatMap: now =>
      Dpop.verify(proof, Dpop.Algorithm.Default, method, uri, now, leeway).mapError(error => RuntimeException(error.toString))

  private def key = DpopKeyPool.derive("exchange-spec", 1).map(_.keyFor(0L))

  def spec = suite("the DPoP token exchange")(
    test("sends no proof at all on a bearer run") {
      for
        endpoint <- endpointAnswering()
        auth <- authFor(endpoint)
        _ <- auth.exchangeCode(AuthCode("c"), CodeVerifier("v"), StubSut.publicClient.creds, None)
        proofs <- endpoint.captured
      yield assertTrue(proofs.isEmpty)
    },
    test("signs the code exchange with a proof auth's own verifier accepts") {
      for
        endpoint <- endpointAnswering()
        auth <- authFor(endpoint)
        signing <- key
        _ <- auth.exchangeCode(AuthCode("c"), CodeVerifier("v"), StubSut.publicClient.creds, Some(signing))
        proofs <- endpoint.captured
        proof <- verify(proofs.head)
      yield assertTrue(proofs.size == 1, proof.jkt == signing.jkt, proof.nonce.isEmpty)
    },
    // §4.2: `ath` binds a proof to a token, and `/token` has no token yet. A proof that carried
    // one here would be binding the request to the credential it is about to replace.
    test("leaves ath off the token endpoint, which has no token to bind to") {
      for
        endpoint <- endpointAnswering()
        auth <- authFor(endpoint)
        signing <- key
        _ <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing))
        proofs <- endpoint.captured
        proof <- verify(proofs.head)
      yield assertTrue(proof.ath.isEmpty)
    },
    suite("§9's nonce handshake")(
      test("retries once with the nonce the challenge carried") {
        for
          endpoint <- endpointAnswering(errorResponse("use_dpop_nonce", Some(issuedNonce)))
          auth <- authFor(endpoint)
          signing <- key
          result <- auth.exchangeCode(AuthCode("c"), CodeVerifier("v"), StubSut.publicClient.creds, Some(signing)).either
          proofs <- endpoint.captured
          first <- verify(proofs.head)
          second <- verify(proofs.last)
        yield assertTrue(
          proofs.size == 2,
          first.nonce.isEmpty,
          second.nonce.contains(issuedNonce),
          result.isRight,
        )
      },
      // What keeps the handshake off the hot path: the nonce is the server's, so one challenge
      // per driver is enough. If this regressed, every token call would cost two round trips and
      // the campaign's `/token` p99 would silently double.
      test("holds the nonce for later calls rather than being challenged again") {
        for
          endpoint <- endpointAnswering(errorResponse("use_dpop_nonce", Some(issuedNonce)))
          auth <- authFor(endpoint)
          signing <- key
          _ <- auth.exchangeCode(AuthCode("c"), CodeVerifier("v"), StubSut.publicClient.creds, Some(signing))
          _ <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing))
          proofs <- endpoint.captured
          third <- verify(proofs.last)
        yield assertTrue(proofs.size == 3, third.nonce.contains(issuedNonce))
      },
      // §8: a nonce arriving on a successful response is a rotation, and the next proof has to
      // carry it or the call after that pays a challenge.
      test("adopts a nonce handed back on a successful response") {
        for
          endpoint <- endpointAnswering(Response.json(tokens).addHeader(Header.Custom("DPoP-Nonce", "rotated")))
          auth <- authFor(endpoint)
          signing <- key
          _ <- auth.exchangeCode(AuthCode("c"), CodeVerifier("v"), StubSut.publicClient.creds, Some(signing))
          _ <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing))
          proofs <- endpoint.captured
          second <- verify(proofs.last)
        yield assertTrue(proofs.size == 2, second.nonce.contains("rotated"))
      },
      // A server that challenges the nonce it just issued is contradicting itself. One retry and
      // no more -- a driver that kept going would spin for the rest of the campaign.
      test("gives up after one retry rather than spinning") {
        for
          endpoint <- endpointAnswering(
            errorResponse("use_dpop_nonce", Some(issuedNonce)),
            errorResponse("use_dpop_nonce", Some(issuedNonce)),
          )
          auth <- authFor(endpoint)
          signing <- key
          failure <- auth.exchangeCode(AuthCode("c"), CodeVerifier("v"), StubSut.publicClient.creds, Some(signing)).either
          proofs <- endpoint.captured
        yield assertTrue(proofs.size == 2, failure.left.exists(_.isInstanceOf[ProtocolError.Misconfigured]))
      },
    ),
    suite("a refused proof is the emulator's fault, not the SUT's")(
      // The distinction the whole campaign's error budget rests on: `invalid_dpop_proof` means
      // this driver's htu, clock or key is wrong, and reporting it as an SUT error would blame
      // the system under test for the instrument's misconfiguration.
      test("maps invalid_dpop_proof on the code exchange to Misconfigured") {
        for
          endpoint <- endpointAnswering(errorResponse("invalid_dpop_proof"))
          auth <- authFor(endpoint)
          signing <- key
          failure <- auth.exchangeCode(AuthCode("c"), CodeVerifier("v"), StubSut.publicClient.creds, Some(signing)).either
        yield assertTrue(failure.left.exists(_.isInstanceOf[ProtocolError.Misconfigured]))
      },
      // The same 400 the refresh path reads as a rejected grant. Counting a bad proof as
      // `loadgen_refresh_rejected_total` would move the one counter §7.4 requires to stay at ~0,
      // and it is read as either a scheduling bug or a real SUT defect.
      test("keeps a refused proof out of the refresh-rejection counter") {
        for
          endpoint <- endpointAnswering(errorResponse("invalid_dpop_proof"))
          auth <- authFor(endpoint)
          signing <- key
          failure <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing)).either
        yield assertTrue(
          failure.left.exists(_.isInstanceOf[ProtocolError.Misconfigured]),
          !failure.left.exists(_.isInstanceOf[ProtocolError.RefreshRejected]),
        )
      },
      // The other direction, which matters just as much: a genuine reuse detection must still
      // reach the counter on a DPoP run.
      test("still reports a genuine grant rejection as a refresh rejection") {
        for
          endpoint <- endpointAnswering(errorResponse("invalid_grant"))
          auth <- authFor(endpoint)
          signing <- key
          failure <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds, Some(signing)).either
        yield assertTrue(failure == Left(ProtocolError.RefreshRejected(RefreshRejection.Unknown("invalid_grant"))))
      },
    ),
  ).provide(TestClient.layer)
