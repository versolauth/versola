package versola.e2e.flows.revocation

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, Status}
import zio.test.*

/** The pull feed (#560): an edge learns of a revocation by asking auth, which is the guarantee the push to a
  * `back_channel_logout_uri` is not.
  *
  * Each case runs a second edge of its own from the staged build, with `revocation.feed.enabled = true` (the staged
  * stack's edge leaves it off, which is the default) and the client under test registered with **no**
  * `back_channel_logout_uri`, so nothing is pushed to any edge: what makes the edge reject the token is the feed
  * and nothing else. The two edges share the database, so the staged one hears of the feed edge's writes by
  * notification; the assertions are made on the feed edge.
  *
  * What is awaited is the edge's refusal, polled within a bound, never a fixed sleep: the feed polls about once a
  * second, and a restarted edge also waits for the lease its stopped predecessor may still hold.
  */
object RevocationFeedFlowSpec extends ZIOSpec[Flows.Setups & OAuthClient & E2EConfig & Client]:

  override val bootstrap: ZLayer[Any, Any, Flows.Setups & OAuthClient & E2EConfig & Client] =
    (E2EConfig.live ++ Client.default) >+> OAuthClient.live >+> Flows.layer

  private val FeedEdgeUrl = "http://localhost:9035"
  // MPORT too: the staged edge holds the default one (8083) for its mutual-TLS listener.
  private val FeedEdgePorts = Map("PORT" -> "9035", "DPORT" -> "9036", "MPORT" -> "9037")

  /** The default `poll-interval` is a second; a revocation reaches the edge within a poll plus a cycle, and a
    * restarted edge may wait out the lease (5 s) a stopped one left. Generous: the refusal is awaited, not its speed. */
  private val Propagation = 30.seconds

  private val FeedOn = "revocation.feed.enabled = true"

  private def startFeedEdge(config: E2EConfig) =
    ExtraService.start(config.edgeLauncher, config.edgeEnvConf, FeedOn, FeedEdgePorts)

  /** Requests through the feed edge: `OAuthClient` addresses the edge by `edgeUrl`. */
  private def viaFeedEdge(client: Client, config: E2EConfig): OAuthClient =
    OAuthClient(client, config.copy(edgeUrl = FeedEdgeUrl))

  private def login(s: Flows.Setup, auth: OAuthClient): Task[String] =
    for
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
    yield token.accessToken

  private def edgeStatus(edge: OAuthClient, accessToken: String): Task[Status] =
    edge.edgePermissions(accessToken).map(_.status)

  /** Polls the edge until it refuses the token, or fails once [[Propagation]] has passed. */
  private def awaitRefused(edge: OAuthClient, accessToken: String): Task[Status] =
    edgeStatus(edge, accessToken)
      .repeat(Schedule.spaced(250.millis) && Schedule.recurUntil[Status](_ == Status.Unauthorized))
      .map(_._2)
      .timeoutFail(RuntimeException(s"the edge still accepted the token after $Propagation"))(Propagation)

  /** A client with no `back_channel_logout_uri`, a user, and the feed edge told about the client. */
  private def clientWithoutPush(auth: OAuthClient, feedEdge: OAuthClient): Task[Flows.Setup] =
    for
      s <- Flows.setupLoginPassword().provideEnvironment(ZEnvironment(auth))
      _ <- auth.flushUserOutbox()
      _ <- auth.syncConfiguration()
      // the edge learns clients from central at its own pace; asked to now, so that the test polls its reaction
      // to the revocation and not its configuration
      _ <- feedEdge.syncEdgeConfiguration()
    yield s

  def spec = suite("Revocation feed")(
    test("an edge rejects a revoked token of a client with no back channel, which only the feed can tell it") {
      for
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        auth <- ZIO.service[OAuthClient]
        edge <- startFeedEdge(config)
        _ <- ExtraService.awaitReady(edge)
        feedEdge = viaFeedEdge(client, config)
        s <- clientWithoutPush(auth, feedEdge)
        revoked <- login(s, auth)
        surviving <- login(s, auth)
        before <- edgeStatus(feedEdge, revoked)
        _ <- auth.revoke(revoked, s.clientId, s.clientSecret)
        after <- awaitRefused(feedEdge, revoked)
        stillServed <- edgeStatus(feedEdge, surviving)
      yield assertTrue(before == Status.Ok).label("the edge accepts the token before it is revoked") &&
        assertTrue(after == Status.Unauthorized).label("the edge rejects it once auth has revoked it, with no push to it") &&
        // one token was named; the client's other tokens are not ended by the feed
        assertTrue(stillServed == Status.Ok).label("a token that was not revoked is still accepted")
    },
    test("an edge that was stopped while the token was revoked rejects it after catching up from its cursor") {
      for
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        auth <- ZIO.service[OAuthClient]
        first <- startFeedEdge(config)
        _ <- ExtraService.awaitReady(first)
        feedEdge = viaFeedEdge(client, config)
        s <- clientWithoutPush(auth, feedEdge)
        revoked <- login(s, auth)
        before <- edgeStatus(feedEdge, revoked)
        // Down for the revocation: nothing is pushed anywhere (no back channel), and this edge cannot ask.
        _ <- ExtraService.stop(first)
        _ <- auth.revoke(revoked, s.clientId, s.clientSecret)
        second <- startFeedEdge(config)
        _ <- ExtraService.awaitReady(second)
        after <- awaitRefused(feedEdge, revoked)
      yield assertTrue(before == Status.Ok).label("the edge accepts the token while it runs") &&
        assertTrue(after == Status.Unauthorized).label("the restarted edge rejects it after reading what it missed")
    },
  ).provideSomeLayer(Scope.default) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(12.minutes)
