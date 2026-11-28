package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Response, Status}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/** #423 end to end, the path the feature exists for: an Android app whose attestation `edge` can verify is let through `enforce`,
  * on the real stack -- central's policy synced to an edge, the DPoP proof of a key checked, the chain the Keystore made for that key
  * read out of the form and held to the policy, and only then auth asked to take the pushed request.
  *
  * A chain to Google's root can only come from a device, so edge is started trusting the test's own authority
  * (`native.attestation.android-roots`, revocation off): a second copy of the staged edge on ports of its own, which serves the same client.
  * The unit suites prove each rule against such chains and against chains real devices made; what only this level shows is that the
  * live layers are wired -- the roots read from the file, the policy that arrived with the sync, the form as a real server decodes it
  * with a field that repeats -- and that a request that passes everything reaches auth.
  */
object NativeAppAttestationAcceptedSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client]:

  private val UpstreamPort = 9117
  private val EdgePorts = Map("PORT" -> "9055", "DPORT" -> "9056", "MPORT" -> "9057")
  private val EdgeUrl = "http://localhost:9055"

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-native-attestation-accepted",
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
    groupCertificate = None,
  )

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client] =
    val base = E2EConfig.live ++ Client.default
    val clients = base >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    base ++ ((clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config))

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val policyPath = "/configuration/clients/attestation"
  private val packageName = "com.example.accepted"
  private val signing = AndroidAttestationChain.sha256("e2e signing certificate".getBytes(StandardCharsets.UTF_8))
  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString

  /** The challenge the SDK makes the Keystore key with: `SHA-256("versola-attest-v1\n" + clientId)`, hashed here, not by edge's code. */
  private def challenge(clientId: String): Array[Byte] =
    AndroidAttestationChain.sha256(s"versola-attest-v1\n$clientId".getBytes(StandardCharsets.UTF_8))

  private def policy(mode: String): Json.Obj =
    Json.Obj(
      "mode" -> Json.Str(mode),
      "android" -> Json.Arr(
        Json.Obj("packageName" -> Json.Str(packageName), "certificateDigests" -> Json.Arr(Json.Str(hex(signing)))),
      ),
    )

  /** A second copy of the staged edge, trusting `rootPem` as Google's root, with no revocation list to fetch, and an address of its own
    * (the `htu` of a proof is the address a request was made to). */
  private def startEdge(rootPem: String): ZIO[Scope & Client & E2EConfig, Throwable, EdgeApi] =
    for
      e2e <- ZIO.service[E2EConfig]
      client <- ZIO.service[Client]
      roots <- ZIO.attemptBlocking(Files.createTempFile("e2e-android-roots-", ".pem").nn)
      _ <- ZIO.attemptBlocking(Files.writeString(roots, rootPem))
      _ <- ZIO.addFinalizer(ZIO.attemptBlocking(Files.deleteIfExists(roots)).ignore)
      extra =
        s"""edge-url = "$EdgeUrl"
           |configuration-cache-refresh-interval = "2 seconds"
           |native { attestation { android-roots = "$roots", android-revocation-enabled = false } }
           |""".stripMargin
      running <- ExtraService.start(e2e.edgeLauncher, e2e.edgeEnvConf, extra, EdgePorts)
      _ <- ExtraService.awaitReady(running)
    yield EdgeApi(client, e2e.copy(edgeUrl = EdgeUrl))

  /** `/native/start` on the extra edge with `chain` as the evidence, signed by `prover`; a `use_dpop_nonce` challenge is answered once. */
  private def start(edge: EdgeApi, prover: DpopProver, clientId: String, chain: List[Array[Byte]]): Task[Response] =
    val evidence =
      ("attestation_platform" -> "android") :: chain.map(der => "attestation_chain" -> Base64.getEncoder.encodeToString(der))
    val attempt = (nonce: Option[String]) =>
      prover.proof(Method.POST, edge.nativeUrl("start", clientId), nonce = nonce)
        .flatMap(proof => edge.native("start", clientId, List("scope" -> "openid offline_access") ++ evidence, Some(proof)))
    for
      first <- attempt(None)
      response <- DpopProver.nonceOf(first) match
        case Some(nonce) if first.status == Status.BadRequest || first.status == Status.Unauthorized => attempt(Some(nonce))
        case _ => ZIO.succeed(first)
    yield response

  private def attestationError(response: Response): Task[Option[String]] =
    response.body.asString.map(_.fromJson[Json.Obj].toOption.flatMap(_.get("attestation_error")).collect { case Json.Str(value) => value })

  def spec = suite("An accepted Android attestation through a real edge (#423)")(
    test("a chain made for the key that signs the proof, for an accepted build, starts the sign-in under enforce; another key's proof does not") {
      ZIO.scoped:
        for
          central <- ZIO.service[CentralApi]
          f <- ZIO.service[EdgeFixture]
          authority = AndroidAttestationChain.make
          edge <- startEdge(authority.rootCertificatePem)
          prover <- DpopProver.make
          stranger <- DpopProver.make
          chain = authority.chainFor(prover.ecPublicKey, challenge(f.clientId), packageName, signing)
          otherApp = authority.chainFor(prover.ecPublicKey, challenge(f.clientId), "com.attacker.app", signing)
          saved <- central.put(policyPath, policy("enforce"), "clientId" -> f.clientId)
          _ <- ZIO.fail(RuntimeException(s"the policy was not saved (${saved.status}): ${saved.body}")).unless(saved.status == Status.Ok)
          // Awaited: the edge has the policy and the client. Until it does the answer is a 404, or a start that nothing asked attestation of.
          accepted <- (edge.syncConfiguration *> start(edge, prover, f.clientId, chain))
            .catchAll(_ => ZIO.succeed(Response.status(Status.ServiceUnavailable)))
            .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[Response](_.status == Status.Ok))
            .timeout(90.seconds)
          acceptedBody <- ZIO.foreach(accepted)(_.body.asString)
          wrongKey <- start(edge, stranger, f.clientId, chain)
          wrongKeyError <- attestationError(wrongKey)
          wrongApp <- start(edge, prover, f.clientId, otherApp)
          wrongAppError <- attestationError(wrongApp)
          noEvidence <- start(edge, prover, f.clientId, Nil)
          noEvidenceError <- attestationError(noEvidence)
        yield assertTrue(
          accepted.exists(_.status == Status.Ok),
          acceptedBody.exists(_.contains("request_uri")),
          // The attested key is not the one that signed this proof: a chain is no use to anyone without its key.
          wrongKey.status == Status.BadRequest,
          wrongKeyError.contains("mismatch"),
          wrongApp.status == Status.BadRequest,
          wrongAppError.contains("app_not_accepted"),
          noEvidence.status == Status.BadRequest,
          noEvidenceError.contains("absent"),
        )
    },
  ) @@ TestAspect.sequential
