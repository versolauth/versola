package versola.edge.nativeapp

import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.edge.dpop.{DpopPolicyService, DpopReplayGuard}
import versola.edge.model.{ApplicationType, AuthorizationPreset, ClientCredential, ClientId, EdgeId, OAuthClient, PresetId}
import versola.edge.{EdgeConfig, OAuthClientService}
import versola.util.{Base64, Dpop, DpopNonce, PrivateClientCertificate, Secret, SecureRandom, SecurityService, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.MessageDigest
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.time.Instant
import java.util.{Date, UUID}
import javax.crypto.spec.SecretKeySpec

/** The rules of #420's four endpoints against a real [[NativeService.Impl]], real AES-GCM
  * sealing and real proof verification -- only auth, on the far side of the mutual-TLS back
  * channel, is a recording fake ([[NativeAuthClientHandshakeSpec]] covers that channel).
  */
object NativeServiceSpec extends ZIOSpecDefault:

  private val EdgeUrl = "https://edge.example"
  private val Issuer = "https://idp.example"
  private val MtlsUrl = "https://mtls.idp.example"
  private val NativeClientId = "mobile-app"
  private val RedirectUri = "https://app.example/callback"
  private val TokenHtu = s"$MtlsUrl/token"
  private val RevokeHtu = s"$MtlsUrl/revoke"

  /** A device key: the P-256 pair a StrongBox/Secure Enclave would hold. */
  private final class DeviceKey:
    private val pair =
      val generator = java.security.KeyPairGenerator.getInstance("EC").nn
      generator.initialize(Curve.P_256.toECParameterSpec)
      generator.generateKeyPair().nn
    private val jwk = ECKey.Builder(Curve.P_256, pair.getPublic.asInstanceOf[ECPublicKey]).build()
    val jkt: String = jwk.computeThumbprint().toString

    def proof(htu: String, htm: String = "POST", nonce: Option[String] = None, iat: Instant = Instant.now()): String =
      val header = JWSHeader.Builder(JWSAlgorithm.ES256).`type`(Dpop.JwtType).jwk(jwk).build()
      val claims = JWTClaimsSet.Builder()
        .claim("htm", htm)
        .claim("htu", htu)
        .jwtID(UUID.randomUUID().toString)
        .issueTime(Date.from(iat))
      nonce.foreach(claims.claim("nonce", _))
      val jwt = SignedJWT(header, claims.build())
      jwt.sign(ECDSASigner(pair.getPrivate.asInstanceOf[ECPrivateKey]))
      jwt.serialize()

  private val certificate: PrivateClientCertificate.Material =
    PrivateClientCertificate(TestCertificates.generate(subject = "CN=mobile-app").bundle).material.toOption.get

  private val nativeClient = OAuthClient(
    id = ClientId(NativeClientId),
    credential = ClientCredential.MutualTls(certificate),
    permissions = Set.empty,
    accessTokenTtl = 1.hour,
    requirePushedAuthorizationRequests = true,
    applicationType = ApplicationType.native,
    redirectUris = Set(RedirectUri),
  )

  /** Same certificate, but registered as a web client: served by `/login`, never here. */
  private val webClient = nativeClient.copy(id = ClientId("web-app"), applicationType = ApplicationType.web)

  private val blobKey = Secret.Bytes32(Array.fill(32)(7.toByte))

  private val keyPair =
    val generator = java.security.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private val nonceSalt = Secret.Bytes32(Array.fill(32)(9.toByte))

  private def config(native: Boolean = true, withNonceDpop: Boolean = false) = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode(Issuer).toOption.get,
    edgeUrl = URL.decode(EdgeUrl).toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
    dpop = Option.when(withNonceDpop)(EdgeConfig.Dpop.default(nonceSalt)),
    native = Option.when(native)(
      EdgeConfig.Native(
        authMutualTlsUrl = URL.decode("https://auth-internal:9008").toOption.get,
        authMutualTlsExternalUrl = Some(URL.decode(MtlsUrl).toOption.get),
        trustedCertificates = Set("/unused/in/this/spec.pem"),
        blobKey = blobKey,
      ),
    ),
  )

  /** What auth would have been sent, one entry per call. */
  private final case class Call(endpoint: String, clientId: String, form: Map[String, List[String]], headers: Headers)

  /** Auth on the far side of the back channel: records each call, answers what the test says. */
  private final class FakeAuth(
      calls: Ref[List[Call]],
      answer: Ref[String => NativeAuthClient.Relayed],
  ) extends NativeAuthClient:
    private def record(endpoint: String, clientId: String, form: Form, headers: Headers) =
      val fields = form.formData.toList.groupMap(_.name)(_.stringValue.getOrElse(""))
      calls.update(_ :+ Call(endpoint, clientId, fields, headers)) *> answer.get.map(_(endpoint))

    override def par(clientId: String, certificate: PrivateClientCertificate.Material, form: Form) =
      record("par", clientId, form, Headers.empty)
    override def token(clientId: String, certificate: PrivateClientCertificate.Material, form: Form, forwardedHeaders: Headers) =
      record("token", clientId, form, forwardedHeaders)
    override def revoke(clientId: String, certificate: PrivateClientCertificate.Material, form: Form, forwardedHeaders: Headers) =
      record("revoke", clientId, form, forwardedHeaders)

  private def json(status: Status, body: String, headers: (String, String)*): NativeAuthClient.Relayed =
    NativeAuthClient.Relayed(
      status,
      Headers(Header.ContentType(MediaType.application.json)) ++ Headers(headers.map(Header.Custom(_, _))*),
      Chunk.fromArray(body.getBytes("UTF-8")),
    )

  private val defaultAnswers: String => NativeAuthClient.Relayed =
    case "par" => json(Status.Created, """{"request_uri":"urn:ietf:params:oauth:request_uri:abc","expires_in":60}""")
    case "token" => json(Status.Ok, """{"access_token":"at","token_type":"DPoP","expires_in":3600,"refresh_token":"rt"}""")
    case _ => NativeAuthClient.Relayed(Status.Ok, Headers.empty, Chunk.empty)

  private final case class Harness(service: NativeService, calls: Ref[List[Call]], answer: Ref[String => NativeAuthClient.Relayed])

  private def harness(
      clients: List[OAuthClient] = List(nativeClient, webClient),
      native: Boolean = true,
      requireNonce: Boolean = false,
  ) =
    for
      calls <- Ref.make(List.empty[Call])
      answer <- Ref.make(defaultAnswers)
      secureRandom <- ZIO.service[SecureRandom]
      securityService <- ZIO.service[SecurityService]
      clientService = new OAuthClientService:
        def findPreset(presetId: PresetId): UIO[Option[AuthorizationPreset]] = ZIO.none
        def listPresets(presetIds: List[PresetId]): UIO[List[AuthorizationPreset]] = ZIO.succeed(Nil)
        def findClient(clientId: ClientId): UIO[Option[OAuthClient]] = ZIO.succeed(clients.find(_.id == clientId))
        def listClients: UIO[List[OAuthClient]] = ZIO.succeed(clients)
        def refreshNow: Task[Unit] = ZIO.unit
      nonceRequired = requireNonce
      policy = new DpopPolicyService:
        def allowedAlgorithms: UIO[Set[Dpop.Algorithm]] = ZIO.succeed(Dpop.Algorithm.Default)
        def requireNonce: UIO[Boolean] = ZIO.succeed(nonceRequired)
        def refreshNow: Task[Unit] = ZIO.unit
      service = NativeService.Impl(
        config(native, withNonceDpop = requireNonce),
        clientService,
        FakeAuth(calls, answer),
        policy,
        DpopReplayGuard.Impl(DpopReplayGuard.MaxSlotEntries),
        secureRandom,
        securityService,
      )
    yield Harness(service, calls, answer)

  private def request(path: String, form: Map[String, String], proof: Option[String]*): Request =
    val body = Body.fromURLEncodedForm(Form(form.toList.map(FormField.simpleField(_, _))*))
    val base = Request.post(URL.decode(s"$EdgeUrl$path").toOption.get, body)
      .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
    proof.flatten.foldLeft(base)((r, p) => r.addHeader(Header.Custom("DPoP", p)))

  private def startRequest(device: DeviceKey, form: Map[String, String] = Map.empty, clientId: String = NativeClientId) =
    val path = s"/native/start/$clientId"
    request(path, form, Some(device.proof(s"$EdgeUrl$path")))

  private def started(h: Harness, device: DeviceKey): ZIO[Any, Any, NativeService.StartResponse] =
    h.service.start(NativeClientId, startRequest(device, Map("scope" -> "openid offline_access")))
      .flatMap(_.body.asString)
      .flatMap(body => ZIO.fromEither(body.fromJson[NativeService.StartResponse]))

  private def completeRequest(
      start: NativeService.StartResponse,
      proof: Option[String],
      state: Option[String] = None,
      iss: String = Issuer,
      blob: Option[String] = None,
  ) =
    request(
      s"/native/complete/$NativeClientId",
      Map("code" -> "code-1", "state" -> state.getOrElse(start.state), "iss" -> iss, "blob" -> blob.getOrElse(start.blob)),
      proof,
    )

  private def failure(effect: IO[NativeError | Throwable, Response]) =
    effect.flip.map {
      case error: NativeError => Some(error)
      case _ => None
    }

  private def isInvalidGrant(error: Option[NativeError]) = error.exists(_.isInstanceOf[NativeError.InvalidGrant])

  private val startSuite = suite("start")(
    test("pushes the request over the back channel bound to the device key, and seals the rest") {
      val device = DeviceKey()
      for
        h <- harness()
        start <- started(h, device)
        calls <- h.calls.get
        par = calls.head
        securityService <- ZIO.service[SecurityService]
        blob <- NativeBlob.open(start.blob, SecretKeySpec(blobKey, "AES"), securityService)
        challenge = Base64.urlEncode(MessageDigest.getInstance("SHA-256").digest(blob.codeVerifier.getBytes("US-ASCII")))
      yield assertTrue(
        calls.map(_.endpoint) == List("par"),
        par.clientId == NativeClientId,
        par.form("dpop_jkt") == List(device.jkt),
        par.form("code_challenge_method") == List("S256"),
        par.form("code_challenge") == List(challenge),
        par.form("redirect_uri") == List(RedirectUri),
        par.form("state") == List(start.state),
        par.form("scope") == List("openid offline_access"),
        // The verifier never leaves edge in the clear.
        !par.form.values.flatten.exists(_ == blob.codeVerifier),
        start.clientId == NativeClientId,
        start.requestUri == "urn:ietf:params:oauth:request_uri:abc",
        start.expiresIn == 60L,
        start.authorizationEndpoint == s"$Issuer/authorize",
        start.tokenEndpoint == TokenHtu,
        start.revocationEndpoint == RevokeHtu,
        blob.jkt == device.jkt,
        blob.clientId == NativeClientId,
        blob.state == start.state,
      )
    },
    test("refuses a start with no device proof, and pushes nothing") {
      for
        h <- harness()
        error <- failure(h.service.start(NativeClientId, request(s"/native/start/$NativeClientId", Map.empty)))
        calls <- h.calls.get
      yield assertTrue(error.exists(_.isInstanceOf[NativeError.InvalidDpopProof]), calls.isEmpty)
    },
    // RFC 9449 §4.3 step 12 / §11.1. The proof is edge's to judge -- it is never forwarded --
    // and the endpoint carries no client credential, so without this an observed one mints a
    // further pushed request at auth for the whole `iat` window.
    test("refuses a start proof already used, and pushes nothing the second time") {
      val device = DeviceKey()
      val path = s"/native/start/$NativeClientId"
      val proof = device.proof(s"$EdgeUrl$path")
      for
        h <- harness()
        first <- h.service.start(NativeClientId, request(path, Map.empty, Some(proof)))
        error <- failure(h.service.start(NativeClientId, request(path, Map.empty, Some(proof))))
        calls <- h.calls.get
      yield assertTrue(
        first.status == Status.Ok,
        error.exists(_.isInstanceOf[NativeError.InvalidDpopProof]),
        calls.size == 1,
      )
    },
    test("refuses a start proof addressed to another URI") {
      val device = DeviceKey()
      for
        h <- harness()
        error <- failure(h.service.start(
          NativeClientId,
          request(s"/native/start/$NativeClientId", Map.empty, Some(device.proof(s"$EdgeUrl/native/start/other"))),
        ))
      yield assertTrue(error.exists(_.isInstanceOf[NativeError.InvalidDpopProof]))
    },
    test("an unknown client, a web client and an edge with no native block all answer as unknown") {
      val device = DeviceKey()
      for
        h <- harness()
        unknown <- failure(h.service.start("nobody", startRequest(device, clientId = "nobody")))
        web <- failure(h.service.start("web-app", startRequest(device, clientId = "web-app")))
        unconfigured <- harness(native = false)
        off <- failure(unconfigured.service.start(NativeClientId, startRequest(device)))
        calls <- h.calls.get
      yield assertTrue(
        unknown.contains(NativeError.UnknownClient),
        web.contains(NativeError.UnknownClient),
        off.contains(NativeError.UnknownClient),
        calls.isEmpty,
      )
    },
    test("refuses a redirect_uri the client did not register") {
      val device = DeviceKey()
      for
        h <- harness()
        error <- failure(h.service.start(NativeClientId, startRequest(device, Map("redirect_uri" -> "https://evil.example/cb"))))
      yield assertTrue(error.exists(_.isInstanceOf[NativeError.InvalidRequest]))
    },
    test("does not let the app choose PKCE, state or the key binding") {
      val device = DeviceKey()
      for
        h <- harness()
        _ <- h.service.start(
          NativeClientId,
          startRequest(device, Map("dpop_jkt" -> "attacker", "state" -> "mine", "code_challenge" -> "x")),
        )
        par <- h.calls.get.map(_.head)
      yield assertTrue(par.form("dpop_jkt") == List(device.jkt), par.form("state") != List("mine"), par.form("code_challenge") != List("x"))
    },
    test("relays auth's refusal of the pushed request as auth stated it") {
      val device = DeviceKey()
      for
        h <- harness()
        _ <- h.answer.set(_ => json(Status.BadRequest, """{"error":"invalid_scope"}"""))
        response <- h.service.start(NativeClientId, startRequest(device))
        body <- response.body.asString
      yield assertTrue(response.status == Status.BadRequest, body.contains("invalid_scope"))
    },
    // §9 / §4.3 step 10: where this edge's policy requires a nonce, a start proof carrying
    // none (or a stale one) is refused with a fresh one, the same as the proxied path.
    test("requires a nonce this edge issued when the policy demands one, and accepts a proof over it") {
      val device = DeviceKey()
      val path = s"/native/start/$NativeClientId"
      for
        h <- harness(requireNonce = true)
        noNonce <- failure(h.service.start(NativeClientId, request(path, Map.empty, Some(device.proof(s"$EdgeUrl$path")))))
        nonce <- Clock.instant.map(DpopNonce.issue(nonceSalt, _))
        withNonce <- h.service.start(
          NativeClientId,
          request(path, Map.empty, Some(device.proof(s"$EdgeUrl$path", nonce = Some(nonce)))),
        )
        calls <- h.calls.get
      yield assertTrue(
        noNonce.exists(_.isInstanceOf[NativeError.NonceRequired]),
        withNonce.status == Status.Ok,
        calls.map(_.endpoint) == List("par"),
      )
    },
    // The nonce this edge's own policy demands is satisfied once, on the proof addressed to
    // edge itself -- it must never be asked of a proof `complete`/`refresh`/`revoke` only
    // forwards. Presenting one over the device's own nonce-less proof here would prove the
    // opposite bug: edge's differently-salted nonce store standing in for auth's, which no
    // proof from a real device could ever satisfy (auth's own `use_dpop_nonce` challenge is
    // relayed to the device untouched, and answered with auth's nonce, not edge's).
    test("does not ask its own nonce policy of a proof addressed to auth, once past start") {
      val device = DeviceKey()
      val path = s"/native/start/$NativeClientId"
      for
        h <- harness(requireNonce = true)
        nonce <- Clock.instant.map(DpopNonce.issue(nonceSalt, _))
        start <- h.service.start(
          NativeClientId,
          request(path, Map("scope" -> "openid offline_access"), Some(device.proof(s"$EdgeUrl$path", nonce = Some(nonce)))),
        )
          .flatMap(_.body.asString)
          .flatMap(body => ZIO.fromEither(body.fromJson[NativeService.StartResponse]))
        response <- h.service.complete(NativeClientId, completeRequest(start, Some(device.proof(TokenHtu))))
        calls <- h.calls.get
      yield assertTrue(response.status == Status.Ok, calls.map(_.endpoint) == List("par", "token"))
    },
  )

  private val completeSuite = suite("complete")(
    test("redeems the code with the sealed verifier, forwarding the device proof unchanged") {
      val device = DeviceKey()
      for
        h <- harness()
        start <- started(h, device)
        proof = device.proof(TokenHtu)
        response <- h.service.complete(NativeClientId, completeRequest(start, Some(proof)))
        body <- response.body.asString
        securityService <- ZIO.service[SecurityService]
        blob <- NativeBlob.open(start.blob, SecretKeySpec(blobKey, "AES"), securityService)
        token <- h.calls.get.map(_.last)
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("\"token_type\":\"DPoP\""),
        token.endpoint == "token",
        token.form("grant_type") == List("authorization_code"),
        token.form("code") == List("code-1"),
        token.form("code_verifier") == List(blob.codeVerifier),
        token.form("redirect_uri") == List(RedirectUri),
        token.headers.get("DPoP").contains(proof),
      )
    },
    test("refuses a proof by another key without spending the code") {
      val device = DeviceKey()
      val thief = DeviceKey()
      for
        h <- harness()
        start <- started(h, device)
        error <- failure(h.service.complete(NativeClientId, completeRequest(start, Some(thief.proof(TokenHtu)))))
        calls <- h.calls.get
      yield assertTrue(isInvalidGrant(error), calls.map(_.endpoint) == List("par"))
    },
    test("refuses a code presented without the blob, or with a blob this edge did not seal") {
      val device = DeviceKey()
      for
        h <- harness()
        start <- started(h, device)
        missing <- failure(h.service.complete(
          NativeClientId,
          request(s"/native/complete/$NativeClientId", Map("code" -> "c", "state" -> start.state, "iss" -> Issuer), Some(device.proof(TokenHtu))),
        ))
        tampered <- failure(h.service.complete(
          NativeClientId,
          completeRequest(start, Some(device.proof(TokenHtu)), blob = Some(start.blob.dropRight(2) + "AA")),
        ))
        calls <- h.calls.get
      yield assertTrue(
        missing.exists(_.isInstanceOf[NativeError.InvalidRequest]),
        isInvalidGrant(tampered),
        calls.map(_.endpoint) == List("par"),
      )
    },
    test("refuses a mismatched state or issuer") {
      val device = DeviceKey()
      for
        h <- harness()
        start <- started(h, device)
        state <- failure(h.service.complete(NativeClientId, completeRequest(start, Some(device.proof(TokenHtu)), state = Some("other"))))
        iss <- failure(h.service.complete(NativeClientId, completeRequest(start, Some(device.proof(TokenHtu)), iss = "https://other-idp.example")))
      yield assertTrue(isInvalidGrant(state), isInvalidGrant(iss))
    },
    test("refuses a blob sealed for another client, and an expired one") {
      val device = DeviceKey()
      for
        h <- harness()
        securityService <- ZIO.service[SecurityService]
        key = SecretKeySpec(blobKey, "AES")
        start <- started(h, device)
        blob <- NativeBlob.open(start.blob, key, securityService)
        foreign <- NativeBlob.seal(blob.copy(clientId = "other-app"), key, securityService)
        expired <- NativeBlob.seal(blob.copy(expiresAt = Instant.now().getEpochSecond - 1), key, securityService)
        foreignError <- failure(h.service.complete(NativeClientId, completeRequest(start, Some(device.proof(TokenHtu)), blob = Some(foreign))))
        expiredError <- failure(h.service.complete(NativeClientId, completeRequest(start, Some(device.proof(TokenHtu)), blob = Some(expired))))
      yield assertTrue(isInvalidGrant(foreignError), isInvalidGrant(expiredError))
    },
    test("refuses a proof addressed to the main listener rather than the mutual-TLS alias") {
      val device = DeviceKey()
      for
        h <- harness()
        start <- started(h, device)
        error <- failure(h.service.complete(NativeClientId, completeRequest(start, Some(device.proof(s"$Issuer/token")))))
      yield assertTrue(error.exists(_.isInstanceOf[NativeError.InvalidDpopProof]))
    },
    test("relays use_dpop_nonce and its DPoP-Nonce untouched") {
      val device = DeviceKey()
      for
        h <- harness()
        start <- started(h, device)
        _ <- h.answer.set(_ => json(Status.BadRequest, """{"error":"use_dpop_nonce"}""", "DPoP-Nonce" -> "n-1"))
        response <- h.service.complete(NativeClientId, completeRequest(start, Some(device.proof(TokenHtu))))
        body <- response.body.asString
        // The retry carries auth's nonce inside the proof, which edge forwards as signed.
        _ <- h.answer.set(defaultAnswers)
        retryProof = device.proof(TokenHtu, nonce = Some("n-1"))
        retried <- h.service.complete(NativeClientId, completeRequest(start, Some(retryProof)))
        forwarded <- h.calls.get.map(_.last.headers.get("DPoP"))
      yield assertTrue(
        response.status == Status.BadRequest,
        body.contains("use_dpop_nonce"),
        response.rawHeader("DPoP-Nonce").contains("n-1"),
        retried.status == Status.Ok,
        forwarded.contains(retryProof),
      )
    },
  )

  private val refreshSuite = suite("token and revoke")(
    test("forwards a refresh with the device proof and relays auth's answer") {
      val device = DeviceKey()
      val proof = device.proof(TokenHtu)
      for
        h <- harness()
        response <- h.service.refresh(
          NativeClientId,
          request(s"/native/token/$NativeClientId", Map("refresh_token" -> "rt-1"), Some(proof))
            .addHeader(Header.Custom("Idempotency-Key", "k-1")),
        )
        call <- h.calls.get.map(_.last)
      yield assertTrue(
        response.status == Status.Ok,
        call.endpoint == "token",
        call.form("grant_type") == List("refresh_token"),
        call.form("refresh_token") == List("rt-1"),
        call.headers.get("DPoP").contains(proof),
        call.headers.get("Idempotency-Key").contains("k-1"),
      )
    },
    test("refuses a refresh with no device proof, or another grant type") {
      val device = DeviceKey()
      for
        h <- harness()
        noProof <- failure(h.service.refresh(NativeClientId, request(s"/native/token/$NativeClientId", Map("refresh_token" -> "rt"))))
        grant <- failure(h.service.refresh(
          NativeClientId,
          request(s"/native/token/$NativeClientId", Map("grant_type" -> "client_credentials", "refresh_token" -> "rt"), Some(device.proof(TokenHtu))),
        ))
        calls <- h.calls.get
      yield assertTrue(
        noProof.exists(_.isInstanceOf[NativeError.InvalidDpopProof]),
        grant.contains(NativeError.UnsupportedGrantType),
        calls.isEmpty,
      )
    },
    test("forwards a revocation with the device proof addressed to the revocation alias") {
      val device = DeviceKey()
      val proof = device.proof(RevokeHtu)
      for
        h <- harness()
        response <- h.service.revoke(NativeClientId, request(s"/native/revoke/$NativeClientId", Map("token" -> "rt-1"), Some(proof)))
        call <- h.calls.get.map(_.last)
      yield assertTrue(
        response.status == Status.Ok,
        call.endpoint == "revoke",
        call.form("token") == List("rt-1"),
        call.headers.get("DPoP").contains(proof),
      )
    },
  )

  def spec = suite("NativeService")(startSuite, completeSuite, refreshSuite)
    .provideShared(SecureRandom.live >+> SecurityService.live) @@ TestAspect.withLiveClock
