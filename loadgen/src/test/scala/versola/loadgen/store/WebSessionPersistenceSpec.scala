package versola.loadgen.store

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.model.{DeviceSession, SessionKind}
import versola.loadgen.protocol.*
import versola.loadgen.config.TargetsConfig
import versola.loadgen.protocol.Credentials
import zio.*
import zio.http.*
import zio.test.*

import java.time.Instant

/** §8.4 end to end for one virtual user, against a real Postgres: the web/cookie login is
  * driven to completion, the `vu_sessions` row it produces is written, and the row is then read
  * back the way a restarted driver reads it (issue #276's integration test).
  *
  * What is exercised here and nowhere else is the join between the two halves: that what
  * [[WebFlows.webOtp]] hands back is exactly what a `vu_sessions` row for a web session needs,
  * and that the row it becomes is one `listLive` will actually resume. Both of those are
  * properties of the SQL and the schema -- `PostgresDeviceSessionRepositorySpec`'s predicate
  * defect was one of them -- so a fake repository would not have caught either.
  *
  * The SUT is [[StubSut]]'s auth and edge over `TestClient`, not the docker-compose stack: the
  * hop sequence, the cookie and its `Max-Age` are real here, while the tokens behind them are
  * not. See the PR for what that leaves unverified.
  */
object WebSessionPersistenceSpec extends LoadgenPostgresSpec:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://central.test", "http://mock.test", StubSut.origin)
  private val request = WebLoginRequest(PresetId(StubSut.preset), None)
  private val credentials = Credentials.PhoneOtp("+70000000201")
  private val accounts = ActionCall(Method.GET, "/resources/core/accounts", None)

  private val loggedInAt = Instant.parse("2026-03-01T09:00:00Z")
  private val userId = 4200L
  private val sessionId = 42L
  private val shard = 3

  private def flows: ZIO[TestClient & Client, ProtocolError, WebFlows] =
    for
      stub <- StubSut.makeWeb(List("credential", "otp"))
      (_, routes) = stub
      _ <- TestClient.addRoutes(routes)
      client <- ZIO.service[Client]
      auth <- HttpAuthClient.make(client, targets, StubSut.registry, StubSut.requestTimeout)
      actions <- EdgeActionClient.make(client, targets, StubSut.requestTimeout)
      edge <- HttpEdgeClient.make(client, targets, actions, StubSut.requestTimeout)
    yield WebFlows(edge, auth, FlowObserver.none, Otp.nonProd(6), StubSut.origin)

  /** The cookie's own `Max-Age` resolved against the clock, which is what the row's
    * `access_expires_at` has to hold for a web session (migration V0002).
    */
  private def expiryOf(cookie: EdgeCookie, at: Instant): ZIO[Any, ProtocolError, Instant] =
    ZIO
      .fromOption(cookie.maxAge)
      .mapBoth(_ => ProtocolError.MalformedResponse("/complete", "EDGE_SESSION without a Max-Age"), maxAge => at.plusSeconds(maxAge.toSeconds))

  private def repository: ZIO[TransactorZIO, Nothing, DeviceSessionRepository] =
    ZIO.serviceWith[TransactorZIO](PostgresDeviceSessionRepository(_))

  /** The row §8.4 produces, written the moment the login returns. */
  private def store(
      repo: DeviceSessionRepository,
      cookie: EdgeCookie,
      ssoSession: Option[SsoSession],
      expiresAt: Instant,
  ): Task[Unit] =
    repo.insert(
      DeviceSession.webCookie(
        id = sessionId,
        userId = userId,
        clientId = "web-otp",
        cookie = cookie.session,
        ssoSession = ssoSession,
        accessExpiresAt = expiresAt,
        acr = Some(Acr.OtpLevel),
        authTime = loggedInAt,
        shard = shard,
      ),
    )

  private def truncate: ZIO[TransactorZIO, Throwable, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE vu_sessions".update.run()).unit)

  def spec = suite("WebSessionPersistence")(
    test("one virtual user completes §8.4 and lands a resumable web-cookie row") {
      for
        _ <- truncate
        repo <- repository
        web <- flows
        (cookie, ssoSession) <- web.webOtp(request, credentials)
        expiresAt <- expiryOf(cookie, loggedInAt)
        session = DeviceSession.webCookie(
          id = sessionId,
          userId = userId,
          clientId = "web-otp",
          cookie = cookie.session,
          ssoSession = ssoSession,
          accessExpiresAt = expiresAt,
          acr = Some(Acr.OtpLevel),
          authTime = loggedInAt,
          shard = shard,
        )
        _ <- repo.insert(session)
        found <- repo.find(sessionId)
        live <- repo.listLive(shard, loggedInAt.plusSeconds(60), 10)
        interrupted <- repo.listInterruptedRotations(shard, 10)
      yield assertTrue(
        found.contains(session),
        // The row a web session is: the cookie is the whole credential, and there is no
        // refresh token or refresh expiry beside it.
        found.exists(_.kind == SessionKind.WebCookie),
        found.exists(_.edgeCookie.contains(cookie.session)),
        found.exists(_.refreshToken.isEmpty),
        found.exists(_.refreshExpiresAt.isEmpty),
        found.exists(_.accessExpiresAt.contains(expiresAt)),
        // The SSO_SESSION auth left behind survives with it -- the one credential that outlives
        // the cookie.
        found.exists(_.ssoSession == ssoSession),
        // A driver restarting mid-campaign must see this session as resumable, which for a web
        // session turns entirely on `access_expires_at` (V0002's COALESCE index).
        live.map(_.id) == Vector(sessionId),
        // And must not see it as a rotation it interrupted: nothing on this path rotates.
        interrupted.isEmpty,
        found.exists(session => !session.rotationInFlight),
      )
    },
    test("the cookie edge rotates on an action replaces the stored one, expiry included") {
      for
        _ <- truncate
        repo <- repository
        web <- flows
        (cookie, ssoSession) <- web.webOtp(request, credentials)
        expiresAt <- expiryOf(cookie, loggedInAt)
        _ <- store(repo, cookie, ssoSession, expiresAt)
        (outcome, next) <- web.businessAction(cookie.session, accounts)
        rotatedExpiry <- expiryOf(
          outcome.rotatedSession.getOrElse(cookie),
          loggedInAt.plusSeconds(600),
        )
        _ <- repo.storeEdgeCookie(sessionId, next, rotatedExpiry)
        found <- repo.find(sessionId)
      yield assertTrue(
        // §8.4's adoption rule, persisted: the row now holds what edge last set, so a driver
        // restart resumes on the live cookie rather than on the one edge already replaced.
        next == EdgeSession(StubSut.rotatedEdgeSession),
        found.exists(_.edgeCookie.contains(next)),
        found.exists(_.accessExpiresAt.contains(rotatedExpiry)),
      )
    },
    test("a web session past its cookie's expiry is no longer resumable, so its traffic re-logs in") {
      for
        _ <- truncate
        repo <- repository
        web <- flows
        (cookie, ssoSession) <- web.webOtp(request, credentials)
        expiresAt <- expiryOf(cookie, loggedInAt)
        _ <- store(repo, cookie, ssoSession, expiresAt)
        stillLive <- repo.listLive(shard, expiresAt.minusSeconds(1), 10)
        expired <- repo.listLive(shard, expiresAt.plusSeconds(1), 10)
      yield assertTrue(stillLive.map(_.id) == Vector(sessionId), expired.isEmpty)
    },
    // The two below are the durability half of §8.4's rotation: what a crash between edge's
    // response and [[DeviceSessionRepository.storeEdgeCookie]] leaves behind, and what a driver
    // that restarts into it does next. Both were raised in review of #307 against the shape of
    // #305's refresh-rotation finding.
    test("a rotation lost to a crash leaves the row wholly un-rotated, never half") {
      for
        _ <- truncate
        repo <- repository
        web <- flows
        (cookie, ssoSession) <- web.webOtp(request, credentials)
        expiresAt <- expiryOf(cookie, loggedInAt)
        _ <- store(repo, cookie, ssoSession, expiresAt)
        // Edge rotated and the driver adopted it in memory; the process dies here, before the
        // critical write.
        (_, next) <- web.businessAction(cookie.session, accounts)
        found <- repo.find(sessionId)
      yield assertTrue(
        next == EdgeSession(StubSut.rotatedEdgeSession),
        // `storeEdgeCookie` puts the cookie and its `access_expires_at` in one UPDATE, so the
        // liveness bound cannot survive without the credential it describes, nor the reverse.
        // The recoverable states are therefore two, not four, and this is the un-rotated one.
        found.exists(_.edgeCookie.contains(cookie.session)),
        found.exists(_.accessExpiresAt.contains(expiresAt)),
      )
    },
    test("a session resumed on the cookie a lost rotation left behind renews instead of blaming the SUT") {
      for
        _ <- truncate
        repo <- repository
        web <- flows
        (cookie, ssoSession) <- web.webOtp(request, credentials)
        expiresAt <- expiryOf(cookie, loggedInAt)
        _ <- store(repo, cookie, ssoSession, expiresAt)
        _ <- web.businessAction(cookie.session, accounts)
        // A restarted driver resumes from the row, which still holds the superseded cookie.
        resumed <- repo.listLive(shard, loggedInAt.plusSeconds(60), 10)
        stale <- ZIO.fromOption(resumed.headOption.flatMap(_.edgeCookie)).orElseFail(new AssertionError("row not resumable"))
        replayed <- web.businessAction(stale, accounts).either
        // What the scenario does with that: a fresh §8.4, then the row carries a live cookie again.
        (renewed, renewedSso) <- web.webOtp(request, credentials)
        renewedExpiry <- expiryOf(renewed, loggedInAt.plusSeconds(120))
        _ <- repo.storeEdgeCookie(sessionId, renewed.session, renewedExpiry)
        found <- repo.find(sessionId)
        live <- repo.listLive(shard, loggedInAt.plusSeconds(180), 10)
        // And the renewed cookie is one the SUT actually honours, not just one the row holds.
        afterRenewal <- web.businessAction(renewed.session, accounts)
      yield assertTrue(
        stale == cookie.session,
        afterRenewal._1.status == Status.Ok,
        // The whole blast radius of a lost rotation: one action spent on a dead cookie, reported
        // as the planned outcome it is. Edge's cookie is the access token it rotated away from
        // and the refresh behind it is already spent, so a replay cannot succeed, cannot rotate
        // again, and cannot be mistaken for a SUT failure -- which is why the web path needs no
        // generation column to make this recoverable, unlike §7.4's refresh rotation.
        replayed == Left(ProtocolError.Unauthorized(accounts.path)),
        renewedSso.isDefined,
        found.exists(_.edgeCookie.contains(renewed.session)),
        found.exists(_.accessExpiresAt.contains(renewedExpiry)),
        live.map(_.id) == Vector(sessionId),
      )
    },
  ).provideSome[TransactorZIO](TestClient.layer) @@ TestAspect.sequential
