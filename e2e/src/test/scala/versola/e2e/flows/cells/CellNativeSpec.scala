package versola.e2e.flows.cells

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Body, Client, Header, MediaType, Method, Request, Response, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.URLEncoder
import java.util.UUID

/** A mobile app's sign-in through edge's native endpoints (#420) where the deployment has two cells
  * (versolauth/versola#547, #1048): the request is pushed to the cell a new sign-in is placed in, the person turns out
  * to be a user of the other cell, and everything after that happens there. What only this shows is that the pieces
  * agree across cells: the pushed request, with the device key it was bound to, reaches the home cell in the seal; the
  * code the home cell issues is exchanged at the home cell's mutual-TLS listener; and the proof the device signed
  * before anyone knew which cell would answer is accepted there, because every cell names that listener alike
  * (`mutual-tls.external-url`).
  */
object CellNativeSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client & CellStand]:

  private val UpstreamPort = 9117

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-cell-native",
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
    scopes = Set("openid", "email", "offline_access"),
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

  // The fixture first and the stand after it, as in `CellStandSpec`: the second cell reads the configuration when it
  // starts, and the native client has to be in it.
  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client & CellStand] =
    val base = E2EConfig.live ++ Client.default
    val clients = base >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    val fixture = (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)
    base ++ ((base ++ fixture) >+> CellStand.layer)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock, TestAspect.sequential)

  private val fixedOtp = "123456"

  /** `/native/start`'s answer, as the SDK reads it. */
  private case class Started(
      client_id: String,
      request_uri: String,
      authorization_endpoint: String,
      state: String,
      blob: String,
      token_endpoint: String,
  ) derives JsonDecoder

  /** RFC 9449 §8: a `DPoP-Nonce` challenge answered once with the nonce signed into a fresh proof. */
  private def withNonce(call: Option[String] => Task[Response]): Task[Response] =
    call(None).flatMap: first =>
      DpopProver.nonceOf(first) match
        case Some(nonce) if first.status == Status.BadRequest || first.status == Status.Unauthorized => call(Some(nonce))
        case _ => ZIO.succeed(first)

  private def segment(jwt: String, index: Int): Task[Json.Obj] =
    for
      text <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(jwt.split('.')(index)), "UTF-8"))
      json <- ZIO.fromEither(text.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield json

  /** A user of cell 2 with a login and a password: registered there by email, then given both through central, which
    * sets them in the cell that holds the user. Returns the user's id, login and password. */
  private def loginUserOfSecond(s: CellStand): ZIO[OAuthClient & CentralApi, Throwable, (UUID, String, String)] =
    for
      auth <- ZIO.service[OAuthClient]
      central <- ZIO.service[CentralApi]
      setup <- s.nextRegistration
      authorize <- s.second.auth.authorize(clientId = Some(setup.clientId), redirectUri = Some(setup.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      card <- s.second.auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- s.second.auth.submitEmail(cookie, setup.email.get, card.csrf)
      otp <- s.second.auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      code <- s.second.auth.submitOtp(cookie, fixedOtp, otp.csrf).flatMap(_.assertRedirect)
      tokens <- s.second.auth.token(
        code,
        authorize.verifier,
        clientId = Some(setup.clientId),
        clientSecret = Some(setup.clientSecret),
        redirectUri = Some(setup.redirectUri),
      ).success
      claims <- segment(tokens.accessToken, 1)
      userId <- ZIO.fromOption(claims.str("sub")).mapBoth(_ => RuntimeException("the token has no sub"), UUID.fromString)
      login <- CentralApi.login("cellnative")
      password = "Cell-Native-Pass-1!"
      patched <- central.patch("/users", Json.Obj("id" -> Json.Str(userId.toString), "login" -> Json.Str(login)))
      _ <- ZIO.fail(RuntimeException(s"central refused the login: ${patched.status} ${patched.body}")).unless(patched.status.isSuccess)
      _ <- auth.flushUserOutbox()
      _ <- auth.setUserPassword(userId, password)
    yield (userId, login, password)

  private def start(prover: DpopProver): ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, Started] =
    for
      edgeApi <- ZIO.service[EdgeApi]
      f <- ZIO.service[EdgeFixture]
      authApi <- ZIO.service[OAuthClient]
      once = withNonce(nonce =>
        prover.proof(Method.POST, edgeApi.nativeUrl("start", f.clientId), nonce = nonce)
          .flatMap(proof => edgeApi.native("start", f.clientId, List("scope" -> "openid offline_access"), Some(proof))),
      )
      // Awaited: the client central registered a moment ago reaches edge's cache and the cells' by a notification.
      response <- once.flatMap: first =>
        if first.status != Status.NotFound && first.status != Status.Unauthorized then ZIO.succeed(first)
        else
          (edgeApi.syncConfiguration *> authApi.syncConfiguration() *> once)
            .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[Response](r => r.status != Status.NotFound && r.status != Status.Unauthorized))
            .timeout(60.seconds)
            .someOrElse(first)
      text <- response.body.asString
      started <- ZIO.fromEither(text.fromJson[Started])
        .mapError(reason => RuntimeException(s"/native/start answered ${response.status}: $text ($reason)"))
    yield started

  private def form(fields: (String, String)*): Body =
    Body.fromString(fields.map((name, value) => s"${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}").mkString("&"))

  def spec = suite("Native app through edge, two cells")(
    test("a device whose sign-in was placed in cell 1 for a user of cell 2 ends with tokens of cell 2, bound to its key, and refreshes them there") {
      for
        s <- ZIO.service[CellStand]
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        edgeApi <- ZIO.service[EdgeApi]
        f <- ZIO.service[EdgeFixture]
        front = OAuthClient(client, config.copy(authUrl = config.edgeUrl))
        (userId, login, password) <- loginUserOfSecond(s)
        prover <- DpopProver.make
        // The device asks edge to start: the request is pushed to the cell a new sign-in is placed in, cell 1.
        started <- start(prover)
        // The browser leg, at the issuer's address as a deployment with cells has it: edge.
        authorizeUrl <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}/authorize")).mapError(RuntimeException(_))
        authorized <-
          client.batched(Request.get(authorizeUrl.addQueryParam("client_id", started.client_id).addQueryParam("request_uri", started.request_uri)))
        firstCookie <- ZIO.fromOption(
          authorized.headers.getAll(Header.SetCookie).collectFirst {
            case h if h.value.name == "__Host-SSO_CONVERSATION" && h.value.content.nonEmpty => h.value.content
          },
        ).orElseFail(RuntimeException(s"no conversation was started for the pushed request: ${authorized.status}"))
        card <- front.getChallenge(firstCookie).assertStep(ConversationStep.Credential)
        // The login typed is cell 2's: cell 1 has the browser repeat the submission there, and cell 2 finishes.
        typed <- front.submitLoginPassword(firstCookie, login, password, card.csrf)
        handedUrl <- ZIO.fromEither(URL.decode(s"${config.edgeUrl}${typed.location}")).mapError(RuntimeException(_))
        finished <- client.batched(
          Request.post(handedUrl, form("login" -> login, "password" -> password, "csrf" -> card.csrf))
            .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
            .addHeader(Header.Custom("Cookie", s"__Host-SSO_CONVERSATION=$firstCookie")),
        )
        back <- ZIO.fromOption(finished.header(Header.Location).map(_.url))
          .orElseFail(RuntimeException(s"cell 2 did not finish the sign-in: ${finished.status}"))
        param = (name: String) =>
          ZIO.fromOption(back.queryParams.getAll(name).headOption).orElseFail(RuntimeException(s"the redirect carries no $name: ${back.encode}"))
        code <- param("code")
        state <- param("state")
        iss <- param("iss")
        // The device completes at edge with a proof it could have signed before the sign-in: the `htu` edge gave it at
        // the start. Edge exchanges the code at the cell the code names.
        completed <- withNonce(nonce =>
          prover.proof(Method.POST, started.token_endpoint, nonce = nonce)
            .flatMap(proof =>
              edgeApi.native("complete", f.clientId, List("code" -> code, "state" -> state, "iss" -> iss, "blob" -> started.blob), Some(proof)),
            ),
        )
        completedBody <- completed.body.asString
        issued <- TokenResult.parse(completed.copy(body = Body.fromString(completedBody))).flatMap(_.success)
          .mapError(error => RuntimeException(s"/native/complete answered ${completed.status}: $completedBody ($error)"))
        header <- segment(issued.accessToken, 0)
        claims <- segment(issued.accessToken, 1)
        refreshToken <- ZIO.fromOption(issued.refreshToken).orElseFail(RuntimeException("no refresh token issued"))
        refreshed <- withNonce(nonce =>
          prover.proof(Method.POST, started.token_endpoint, nonce = nonce)
            .flatMap(proof => edgeApi.native("token", f.clientId, List("refresh_token" -> refreshToken), Some(proof))),
        )
        refreshedTokens <- TokenResult.parse(refreshed).flatMap(_.success)
        refreshedHeader <- segment(refreshedTokens.accessToken, 0)
      yield assertTrue(started.request_uri.contains(s"v1.${s.first.id}."), firstCookie.startsWith(s"v1.${s.first.id}."))
        .label(s"the request was pushed to cell 1 and the sign-in started there: ${started.request_uri}") &&
        assertTrue(typed.response.status == Status.TemporaryRedirect, typed.location.contains(s"handoff=v1.${s.second.id}."))
          .label(s"the submission was handed to cell 2: ${typed.response.status} ${typed.location}") &&
        assertTrue(code.startsWith(s"v1.${s.second.id}.")).label(s"cell 2 issued the code: $code") &&
        assertTrue(header.str("cell").contains(s.second.id), claims.str("sub").contains(userId.toString))
          .label(s"edge exchanged it at cell 2, for the user cell 2 holds: ${header.str("cell")}") &&
        assertTrue(issued.tokenType == "DPoP", claims.get("cnf").flatMap(_.as[Json.Obj].toOption).flatMap(_.str("jkt")).contains(prover.jkt))
          .label("the token is bound to the device key the request was pushed with in cell 1") &&
        assertTrue(refreshedTokens.tokenType == "DPoP", refreshedHeader.str("cell").contains(s.second.id))
          .label("and the refresh goes to cell 2 with a proof for the same address")
    },
  )
