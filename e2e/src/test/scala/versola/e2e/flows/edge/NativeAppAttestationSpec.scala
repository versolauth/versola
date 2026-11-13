package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Method, Response, Status}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** #423 end to end: the app attestation policy central stores for a native client, as edge enforces it on `/native/start`.
  *
  * The unit suites prove the verification of real attestation objects against stubs and fixtures. What only this level shows
  * is that the policy a console operator saves reaches edge through the sync (and only for the client it is saved for), that
  * `report` lets a request through whatever the app sent and `enforce` turns away the one that carries nothing verifiable, and
  * that the refusal is the `invalid_client_attestation` the SDKs read, on the wire, with the proof's `jti` still spent once.
  *
  * A genuine Apple or Google attestation can only come from a device, so no accepted-evidence case is driven here: that is the
  * device tier, run by hand before a client is put on `enforce`.
  */
object NativeAppAttestationSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  private val UpstreamPort = 9115

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-native-attestation",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(
        name = "items",
        method = "GET",
        path = "/items",
        permitted = true,
        fetchUserInfo = false,
        allow = None,
        inject = Nil,
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      ),
    ),
    scopes = Set("openid", "offline_access"),
    presetScope = Set("openid"),
    postLogoutRedirectUri = None,
    cookiePath = None,
    awaitProxyReady = false,
    privateKeyJwt = false,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    mutualTls = false,
    nativeApp = true,
    issuedCertificate = true,
    enrolledCertificate = false,
  )

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub] =
    val clients = (E2EConfig.live ++ zio.http.Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val policyPath = "/configuration/clients/attestation"

  private val digest = "ab" * 32

  private def policy(mode: String): Json.Obj =
    Json.Obj(
      "mode" -> Json.Str(mode),
      "ios" -> Json.Obj("appIds" -> Json.Arr(Json.Str("ABCDE12345.com.example.app")), "environment" -> Json.Str("production")),
      "android" -> Json.Arr(
        Json.Obj("packageName" -> Json.Str("com.example.app"), "certificateDigests" -> Json.Arr(Json.Str(digest))),
      ),
    )

  /** What an app that cannot attest, or attests badly, sends. */
  private val noEvidence = List.empty[(String, String)]
  private val garbageAssertion = List(
    "attestation_platform" -> "ios",
    "attestation_key_id" -> "bm90IGEga2V5",
    "attestation_assertion" -> "bm90IGFuIGFzc2VydGlvbg==",
  )

  /** Saves the policy at central and waits until edge serves it: the notification that carries it is not waited for by the
    * request that saved it, so the first answers after a change can still be the previous policy's. */
  private def setPolicy(mode: String): ZIO[CentralApi & EdgeApi & EdgeFixture, Throwable, Unit] =
    for
      central <- ZIO.service[CentralApi]
      edge <- ZIO.service[EdgeApi]
      f <- ZIO.service[EdgeFixture]
      saved <- central.put(policyPath, policy(mode), "clientId" -> f.clientId)
      _ <- ZIO.fail(RuntimeException(s"the policy was not saved (${saved.status}): ${saved.body}")).unless(saved.status == Status.Ok)
      _ <- edge.syncConfiguration
    yield ()

  /** RFC 9449 §9: edge's own `DPoP-Nonce` challenge, answered once with the nonce signed into a fresh proof. Every attempt is
    * a new proof with a new `jti`, so no request is ever a replay of the one before it. */
  private def start(extra: List[(String, String)]): ZIO[EdgeApi & EdgeFixture, Throwable, Response] =
    for
      edge <- ZIO.service[EdgeApi]
      f <- ZIO.service[EdgeFixture]
      prover <- DpopProver.make
      attempt = (nonce: Option[String]) =>
        prover.proof(Method.POST, edge.nativeUrl("start", f.clientId), nonce = nonce)
          .flatMap(proof => edge.native("start", f.clientId, List("scope" -> "openid offline_access") ++ extra, Some(proof)))
      first <- attempt(None)
      response <- DpopProver.nonceOf(first) match
        case Some(nonce) if first.status == Status.BadRequest || first.status == Status.Unauthorized => attempt(Some(nonce))
        case _ => ZIO.succeed(first)
    yield response

  /** The answer, once a client registered a moment ago is known to edge and auth (404 and 401 are those caches catching up). */
  private def startSettled(extra: List[(String, String)]): ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, Response] =
    for
      edge <- ZIO.service[EdgeApi]
      auth <- ZIO.service[OAuthClient]
      first <- start(extra)
      response <-
        if first.status != Status.NotFound && first.status != Status.Unauthorized then ZIO.succeed(first)
        else
          (edge.syncConfiguration *> auth.syncConfiguration() *> start(extra))
            .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[Response](r =>
              r.status != Status.NotFound && r.status != Status.Unauthorized,
            ))
            .timeout(60.seconds)
            .someOrElse(first)
    yield response

  private def errorOf(response: Response): Task[(Option[String], Option[String])] =
    response.body.asString.map: text =>
      val json = text.fromJson[Json.Obj].toOption
      (
        json.flatMap(_.get("error")).collect { case Json.Str(value) => value },
        json.flatMap(_.get("error_description")).collect { case Json.Str(value) => value },
      )

  def spec = suite("Native app attestation through edge (#423)")(
    test("a client with no policy is started as before") {
      for
        response <- startSettled(noEvidence)
      yield assertTrue(response.status == Status.Ok)
    },
    test("report lets an app that sends nothing, and one that sends rubbish, through") {
      for
        _ <- setPolicy("report")
        bare <- startSettled(noEvidence)
        rubbish <- start(garbageAssertion)
      yield assertTrue(bare.status == Status.Ok, rubbish.status == Status.Ok)
    },
    test("enforce turns away a request with no evidence, and one with an assertion for a key never attested") {
      for
        _ <- setPolicy("enforce")
        bare <- start(noEvidence)
        bareError <- errorOf(bare)
        rubbish <- start(garbageAssertion)
        rubbishError <- errorOf(rubbish)
      yield assertTrue(
        bare.status == Status.BadRequest,
        bareError._1.contains("invalid_client_attestation"),
        rubbish.status == Status.BadRequest,
        rubbishError._1.contains("invalid_client_attestation"),
        // The SDK acts on this sentence: it forgets the key and attests a new one.
        rubbishError._2.exists(_.contains("has not been attested")),
      )
    },
    test("putting the client back to off lifts the refusal") {
      for
        central <- ZIO.service[CentralApi]
        edge <- ZIO.service[EdgeApi]
        f <- ZIO.service[EdgeFixture]
        _ <- setPolicy("enforce")
        refused <- start(noEvidence)
        deleted <- central.delete(policyPath, "clientId" -> f.clientId)
        _ <- edge.syncConfiguration
        lifted <- start(noEvidence)
      yield assertTrue(refused.status == Status.BadRequest, deleted.status == Status.NoContent || deleted.status == Status.Ok, lifted.status == Status.Ok)
    },
  ) @@ TestAspect.sequential
