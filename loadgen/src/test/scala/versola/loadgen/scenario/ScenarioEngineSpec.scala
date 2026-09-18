package versola.loadgen.scenario

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.config.*
import versola.loadgen.metrics.LatencyRecorder
import versola.loadgen.model.*
import versola.loadgen.protocol.*
import versola.loadgen.scheduler.{ArrivalProcess, RandomSource, ScheduleLag, ThinkTimeTable, VaryingRate}
import versola.loadgen.store.*
import zio.*
import zio.http.*
import zio.test.*

import java.time.Instant

/** Issue #278's integration tests: one virtual user driven through a whole session against a
  * stub SUT and the emulator's real Postgres, and a driver killed mid-refresh restarted into the
  * row it left behind.
  *
  * Both are joins between halves that a unit test cannot exercise: what §8's flows hand back has
  * to be exactly what a `vu_sessions` row needs, and §7.4's discipline is stated against the
  * persisted generation columns rather than against anything in memory. The SUT is
  * [[ScenarioSut]] over `TestClient`, so the hop sequences, the cookie rotation and the reuse
  * detection are real while the tokens behind them are not.
  */
object ScenarioEngineSpec extends versola.loadgen.store.LoadgenPostgresSpec:

  private val targets = TargetsConfig(ScenarioSut.authUrl, ScenarioSut.edgeUrl, "http://central.test", "http://mock.test", ScenarioSut.origin)
  private val shard = ShardConfig(0, 1)

  private val accounts = BusinessActionConfig("accounts", 30.0, "GET", "/resources/core/accounts", None)
  private val balance = BusinessActionConfig("balance", 20.0, "GET", "/resources/core/accounts/{accountId}/balance", None)
  private val payment = BusinessActionConfig("payment", 10.0, "POST", "/resources/pay/transfers", Some(ScenarioSut.stepUpAcr))
  private val paymentPath = payment.path

  private val clients = ScenarioClients("mobile-otp", "mobile-otp-password", "mobile-passkey", PresetId(ScenarioSut.preset), "openid phone")

  private def config(
      paymentProbability: Double = 0.0,
      accessTokenTtl: Duration = 15.minutes,
      extraRefreshProbability: Double = 0.0,
      logout: Double = 0.0,
      fullLogin: Double = 0.0,
      mobileMean: Double = 3.0,
  ): SessionConfig =
    SessionConfig(
      fullLoginProbability = FullLoginProbabilityConfig(fullLogin, fullLogin),
      actionCount = ActionCountConfig(mobileMean, mobileMean, 0.05),
      // The table's floor is 200 ms, so a session of a few actions costs under a second of
      // wall clock and the think time is still a real gap rather than a mocked one.
      thinkTime = ThinkTimeConfig(1.milli, 0.1),
      paymentProbability = paymentProbability,
      extraRefreshProbability = extraRefreshProbability,
      logoutProbability = LogoutProbabilityConfig(logout, logout),
      accessTokenTtl = accessTokenTtl,
      refreshTokenTtl = Duration.fromSeconds(30L * 24 * 3600),
    )

  private def user(id: Long, platform: Platform, credential: CredentialKind = CredentialKind.Otp): VirtualUser =
    VirtualUser(
      id = id,
      sutUserId = Some(java.util.UUID.nameUUIDFromBytes(id.toString.getBytes("UTF-8"))),
      phone = s"+7000000${1000 + id}",
      password = if credential == CredentialKind.OtpPassword then Some("hunter2") else None,
      activityClass = ActivityClass.Regular,
      platform = platform,
      credential = credential,
      role = UserRole.RetailUser,
      passkeyKey = None,
      passkeyCredId = None,
      state = VirtualUserState.Registered,
      shard = shard.index,
      lastSeenAt = None,
    )

  /** Collects what a driver would have deferred, so the assertions can tell an awaited critical
    * write from a bookkeeping one (§7.5).
    */
  private final class RecordingBuffer(updates: Ref[Vector[DeferredUpdate]]) extends WriteBehindBuffer:
    override def enqueue(update: DeferredUpdate): UIO[Unit] = updates.update(_ :+ update)
    override def flush: UIO[Unit] = ZIO.unit
    override def droppedOnEnqueue: UIO[Long] = ZIO.succeed(0L)
    override def droppedOnFlush: UIO[Long] = ZIO.succeed(0L)
    override def droppedTotal: UIO[Long] = ZIO.succeed(0L)
    override def pending: UIO[Int] = updates.get.map(_.length)
    def recorded: UIO[Vector[DeferredUpdate]] = updates.get

  private final case class Harness(
      runner: SessionRunner,
      sessions: DeviceSessionRepository,
      sut: ScenarioSut.State,
      recorder: ScenarioRecorder,
      buffer: RecordingBuffer,
  )

  private def harness(
      settings: SessionConfig,
      actions: List[BusinessActionConfig],
      routes: Routes[Any, Nothing],
      sut: ScenarioSut.State,
  ): ZIO[Scope & TransactorZIO & TestClient & Client, Throwable, Harness] =
    for
      _ <- TestClient.addRoutes(routes)
      client <- ZIO.service[Client]
      auth <- HttpAuthClient.make(client, targets, ScenarioSut.registry, ScenarioSut.requestTimeout).mapError(failed)
      calls <- EdgeActionClient.make(client, targets, ScenarioSut.requestTimeout).mapError(failed)
      edge <- HttpEdgeClient.make(client, targets, calls, ScenarioSut.requestTimeout).mapError(failed)
      latencies <- LatencyRecorder.make
      updates <- Ref.make(Vector.empty[DeferredUpdate])
      buffer = RecordingBuffer(updates)
      recorder <- ScenarioRecorder.make(latencies, buffer, 1.0)
      mobile = MobileFlows(auth, calls, ScenarioSut.registry, recorder, Otp.nonProd(6), ScenarioSut.origin)
      web = WebFlows(edge, auth, recorder, Otp.nonProd(6), ScenarioSut.origin)
      sessions <- ZIO.serviceWith[TransactorZIO](PostgresDeviceSessionRepository(_))
      business <- ZIO.fromEither(BusinessActions.from(actions)).mapError(AssertionError(_))
      thinkTime = ThinkTimeTable.build(settings.thinkTime, RandomSource.seeded(1L))
      runner = SessionRunner(mobile, web, sessions, buffer, business, SessionIds.make(shard), thinkTime, clients, settings, None)
    yield Harness(runner, sessions, sut, recorder, buffer)

  /** A protocol error is not a `Throwable`, and these tests want a failure to end the test
    * rather than be part of its result.
    */
  private def failed(error: ProtocolError): AssertionError = AssertionError(error.toString)

  private def truncate: ZIO[TransactorZIO, Throwable, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE vu_sessions".update.run()).unit)

  private def truncateUsers: ZIO[TransactorZIO, Throwable, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE vu_users CASCADE".update.run()).unit)

  private def users: ZIO[TransactorZIO, Nothing, VirtualUserRepository] =
    ZIO.serviceWith[TransactorZIO](PostgresVirtualUserRepository(_))

  private def rows: ZIO[TransactorZIO, Throwable, Vector[DeviceSession]] =
    ZIO.serviceWithZIO[TransactorZIO](transactor => PostgresDeviceSessionRepository(transactor).listLive(shard.index, Instant.now(), 100))

  /** A plan drawn from the same seed the run will use, so the assertions can name the session's
    * own length and payment slot instead of a number that happens to hold today.
    */
  private def planFor(settings: SessionConfig, platform: Platform, seed: Long): SessionPlan =
    SessionPlan.draw(settings, platform, RandomSource.seeded(seed))

  def spec = suite("ScenarioEngine")(
    test("§8.1 end to end: a user with no live session logs in, acts, and lands a resumable row") {
      val settings = config()
      val plan = planFor(settings, Platform.Mobile, 101L)
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        _ <- harnessed.runner.run(user(1L, Platform.Mobile), RandomSource.seeded(101L)).mapError(failed)
        hops <- sut.paths
        stored <- rows
        refused <- sut.refusedRefreshes
        deferred <- harnessed.buffer.recorded
      yield assertTrue(
        // §8.1's hop sequence, then the session's actions on top of it.
        hops.startsWith(Vector("GET /authorize", "GET /challenge", "POST /challenge/phone", "GET /challenge", "POST /challenge/otp", "POST /token")),
        hops.count(_.endsWith("/resources/core/accounts")) >= 1,
        hops.count(hop => hop.startsWith("GET /resources") || hop.startsWith("POST /resources")) == plan.actionCount,
        // The row a mobile session is, and one the next arrival can resume: a live refresh
        // token, a settled generation pair, and an expiry in the future.
        stored.map(_.userId) == Vector(1L),
        stored.forall(_.kind == SessionKind.MobileToken),
        stored.forall(_.refreshToken.isDefined),
        stored.forall(_.refreshExpiresAt.isDefined),
        stored.forall(!_.rotationInFlight),
        stored.forall(_.shard == shard.index),
        refused == 0,
        // §7.5: the session's own bookkeeping is deferred, never awaited on the critical path.
        deferred.exists {
          case DeferredUpdate.UserSeen(1L, _) => true
          case _ => false
        },
        deferred.exists {
          case DeferredUpdate.SessionTouched(_) => true
          case _ => false
        },
      )
    },
    test("§8.5: the next arrival on a live session refreshes instead of logging in, and rotates the stored token") {
      val settings = config()
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        virtual = user(2L, Platform.Mobile)
        _ <- harnessed.runner.run(virtual, RandomSource.seeded(202L)).mapError(failed)
        afterLogin <- rows
        first = afterLogin.head
        _ <- harnessed.runner.run(virtual, RandomSource.seeded(303L)).mapError(failed)
        afterResume <- rows
        hops <- sut.paths
        refused <- sut.refusedRefreshes
      yield assertTrue(
        // One login, and a refresh for the second arrival rather than a second conversation.
        hops.count(_ == "GET /authorize") == 1,
        afterResume.map(_.id) == afterLogin.map(_.id),
        // §7.4 step 4: the row holds the rotated token, under a settled generation pair.
        afterResume.head.refreshToken != first.refreshToken,
        afterResume.head.generation > first.generation,
        !afterResume.head.rotationInFlight,
        // And the token was presented exactly once, which is what keeps this metric at 0.
        refused == 0,
      )
    },
    test("§7.4: a driver killed between the generation bump and the exchange retires the session rather than replaying it") {
      val settings = config()
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        virtual = user(3L, Platform.Mobile)
        _ <- harnessed.runner.run(virtual, RandomSource.seeded(404L)).mapError(failed)
        live <- rows
        // The crash: the intent to rotate is persisted, and the process dies before POST /token.
        _ <- harnessed.sessions.bumpGeneration(live.head.id)
        interrupted <- harnessed.sessions.listInterruptedRotations(shard.index, 10)
        // The restart.
        retired <- SessionRecovery.retireInterruptedRotations(harnessed.sessions, shard)
        afterRecovery <- harnessed.sessions.find(live.head.id)
        before <- sut.paths
        // The user's next arrival, which must be a full login and not a replay of the token the
        // dead driver may already have spent.
        _ <- harnessed.runner.run(virtual, RandomSource.seeded(505L)).mapError(failed)
        after <- sut.paths
        refused <- sut.refusedRefreshes
        resumed <- rows
      yield assertTrue(
        interrupted.map(_.id) == Vector(live.head.id),
        retired == 1,
        afterRecovery.isEmpty,
        // A second conversation, because there is nothing left to resume.
        after.drop(before.length).contains("GET /authorize"),
        // The point of the whole discipline: the SUT was never asked to honour the stranded
        // token, so `loadgen_refresh_rejected_total` stays where §7.4 fixes it.
        refused == 0,
        resumed.map(_.userId) == Vector(3L),
        resumed.forall(!_.rotationInFlight),
      )
    },
    test("§7.4: a step-up demand is answered once and the refused action replayed, with the new acr persisted") {
      val settings = config(paymentProbability = 1.0, mobileMean = 6.0)
      val plan = planFor(settings, Platform.Mobile, 606L)
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance, payment), routes, sut)
        _ <- harnessed.runner.run(user(4L, Platform.Mobile), RandomSource.seeded(606L)).mapError(failed)
        hops <- sut.paths
        stored <- rows
      yield assertTrue(
        plan.paymentAction.isDefined,
        // The 401, the step-up conversation, and exactly one replay -- not a loop.
        hops.count(_ == s"POST $paymentPath") == 2,
        hops.count(_ == "GET /authorize") == 2,
        // A step-up re-authenticates one factor, not the whole conversation.
        hops.count(_ == "POST /challenge/phone") == 1,
        hops.count(_ == "POST /challenge/otp") == 2,
        stored.forall(_.acr.contains(ScenarioSut.stepUpAcr)),
        stored.forall(!_.rotationInFlight),
      )
    },
    test("a 403 is an outcome, not a failure: the session carries on to its remaining actions") {
      val settings = config(mobileMean = 6.0)
      val plan = planFor(settings, Platform.Mobile, 707L)
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath, forbiddenPaths = Set(accounts.path))
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        _ <- harnessed.runner.run(user(5L, Platform.Mobile), RandomSource.seeded(707L)).mapError(failed)
        hops <- sut.paths
        stored <- rows
        abort <- harnessed.recorder.abort
        taxonomy <- harnessed.recorder.taxonomy
      yield assertTrue(
        // Every planned action was made, the forbidden ones included.
        hops.count(hop => hop.startsWith("GET /resources") || hop.startsWith("POST /resources")) == plan.actionCount,
        stored.map(_.userId) == Vector(5L),
        // Counted, and charged to nothing that invalidates the run.
        abort.isEmpty,
        taxonomy.total > 0,
      )
    },
    test("§8.4: a web session logs in over the cookie path and adopts every rotation edge issues") {
      val settings = config(mobileMean = 3.0)
      for
        _ <- truncate
        stub <- ScenarioSut.web(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        _ <- harnessed.runner.run(user(6L, Platform.Web), RandomSource.seeded(808L)).mapError(failed)
        hops <- sut.paths
        stored <- rows
        issued <- sut.liveCookie
      yield assertTrue(
        hops.startsWith(Vector(s"GET /login/${ScenarioSut.preset}", "GET /authorize")),
        hops.contains("GET /complete"),
        stored.forall(_.kind == SessionKind.WebCookie),
        stored.forall(_.edgeCookie.isDefined),
        stored.forall(_.refreshToken.isEmpty),
        // The row holds what edge last set, not the value the login started with, so a restart
        // resumes on a cookie the SUT still honours.
        stored.forall(_.edgeCookie.exists(_.value == issued)),
        // And it is not the one the login started with, so a rotation really was adopted.
        issued != "edge-session-2",
      )
    },
    test("§4: a defect in the arrival generator ends the loop instead of parking it on a queue nobody fills") {
      // A zero rate ceiling is `ArrivalProcess`'s own precondition, checked at the point of use:
      // it dies rather than returning, and the death happens on the generator's fiber.
      val settings = config()
      for
        _ <- truncate
        _ <- truncateUsers
        repo <- users
        _ <- repo.insertAll(Chunk(user(30L, Platform.Mobile)))
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        pool <- UserPool.paged(repo, shard, UserPool.pageSize)
        busy <- BusyUsers.make
        lag <- ScheduleLag.make
        now <- Clock.instant
        loop = DriverLoop(
          ArrivalProcess.startingAt(now, RandomSource.seeded(41L)),
          VaryingRate(0.0, now.plusSeconds(60), _ => 1.0),
          pool,
          busy,
          harnessed.runner,
          harnessed.recorder,
          lag,
          RandomSource.seeded(42L),
          64,
        )
        exit <- ZIO.scoped(loop.run).exit.timeout(30.seconds)
      yield assertTrue(
        // The point is that this terminates at all: a dispatcher that only learns of the horizon
        // through the queue blocks forever when the generator dies before reaching it, and a
        // misconfigured campaign then produces neither load nor a reason.
        exit.exists(_.isFailure),
      )
    },
    test("an explicit logout ends the session at the SUT and leaves no row to resume") {
      val settings = config(logout = 1.0)
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        _ <- harnessed.runner.run(user(7L, Platform.Mobile), RandomSource.seeded(909L)).mapError(failed)
        hops <- sut.paths
        stored <- rows
        deferred <- harnessed.buffer.recorded
      yield assertTrue(
        hops.contains("GET /logout"),
        stored.isEmpty,
        // A session that logged out has no row left to touch, so only the user's own last-seen
        // is deferred.
        deferred.count {
          case DeferredUpdate.SessionTouched(_) => true
          case _ => false
        } == 0,
      )
    },
    test("§7.4: a web step-up carries the session's SSO_SESSION, so auth asks only for the missing factor") {
      val settings = config(paymentProbability = 1.0, mobileMean = 6.0)
      val plan = planFor(settings, Platform.Web, 1010L)
      for
        _ <- truncate
        stub <- ScenarioSut.web(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance, payment), routes, sut)
        _ <- harnessed.runner.run(user(8L, Platform.Web), RandomSource.seeded(1010L)).mapError(failed)
        hops <- sut.paths
        stored <- rows
      yield assertTrue(
        plan.paymentAction.isDefined,
        // Two authorizations -- the login and the step-up -- but only one credential factor: the
        // step-up is answered on the session the login left behind. Without the `SSO_SESSION` on
        // the authorize hop the SUT cannot know that, and the second conversation is a full login
        // reported as a step-up.
        hops.count(_ == "GET /authorize") == 2,
        hops.count(_ == "POST /challenge/phone") == 1,
        hops.count(_ == "POST /challenge/otp") == 2,
        // The step-up ran the web path end to end, so it also ends in a fresh cookie.
        hops.count(_ == "GET /complete") == 2,
        hops.count(_ == s"POST $paymentPath") == 2,
        stored.forall(_.acr.contains(ScenarioSut.stepUpAcr)),
        stored.forall(_.edgeCookie.isDefined),
      )
    },
    test("§2.3: the plan's extra refresh is spent once, however many actions outlive the access token") {
      // Every token the SUT mints is already expired, so the staleness test is true at every
      // action and only the plan's own allowance keeps the count down.
      val settings = config(extraRefreshProbability = 1.0, mobileMean = 8.0)
      val plan = planFor(settings, Platform.Mobile, 1111L)
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath, accessTokenSeconds = 0L)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        _ <- harnessed.runner.run(user(9L, Platform.Mobile), RandomSource.seeded(1111L)).mapError(failed)
        hops <- sut.paths
        refused <- sut.refusedRefreshes
        stored <- rows
      yield assertTrue(
        plan.extraRefresh,
        // Enough actions that "once per session" and "once per action" are different numbers.
        plan.actionCount >= 3,
        // The login's exchange and exactly one refresh.
        hops.count(_ == "POST /token") == 2,
        refused == 0,
        stored.forall(!_.rotationInFlight),
      )
    },
    test("a session whose refresh is refused stops there rather than acting on the expired token") {
      val settings = config(extraRefreshProbability = 1.0, mobileMean = 8.0)
      for
        _ <- truncate
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath, accessTokenSeconds = 0L, refuseRefresh = true)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        virtual = user(10L, Platform.Mobile)
        _ <- harnessed.runner.run(virtual, RandomSource.seeded(1212L)).mapError(failed)
        hops <- sut.paths
        stored <- rows
      yield assertTrue(
        // The refresh was attempted and refused.
        hops.count(_ == "POST /token") == 2,
        // And nothing was attempted afterwards. Carrying on with the credential the refresh just
        // retired would put a request the scenario never planned on the wire, and the SUT's 401
        // would land in the error budget as if the SUT had misbehaved.
        hops.count(hop => hop.startsWith("GET /resources") || hop.startsWith("POST /resources")) == 0,
        // §7.4 step 5: the row is retired, so the next arrival logs in rather than resuming.
        stored.isEmpty,
      )
    },
    test("the pool walks its shard in id order, skips users that are not registered, and wraps around") {
      for
        _ <- truncateUsers
        repo <- users
        _ <- repo.insertAll(
          Chunk(
            user(10L, Platform.Mobile),
            user(11L, Platform.Mobile).copy(state = VirtualUserState.Planned),
            user(12L, Platform.Mobile).copy(state = VirtualUserState.Broken),
            user(13L, Platform.Mobile),
          ),
        )
        pool <- UserPool.paged(repo, shard, 2)
        picked <- ZIO.foreach(1 to 4)(_ => pool.next).map(_.flatten.map(_.id))
      yield assertTrue(
        // A `planned` user has no SUT identity to log in with and a `broken` one is terminal by
        // design, so neither is ever scheduled.
        picked == Seq(10L, 13L, 10L, 13L),
      )
    },
    test("an unseeded shard yields nothing rather than looping forever") {
      for
        _ <- truncateUsers
        repo <- users
        pool <- UserPool.paged(repo, shard, 2)
        picked <- pool.next
      yield assertTrue(picked.isEmpty)
    },
    test("§7.2: the driver loop runs its schedule to the horizon, one session per arrival") {
      val settings = config(mobileMean = 1.0)
      for
        _ <- truncate
        _ <- truncateUsers
        repo <- users
        _ <- repo.insertAll(Chunk(user(20L, Platform.Mobile), user(21L, Platform.Mobile), user(22L, Platform.Mobile)))
        stub <- ScenarioSut.mobile(List("credential", "otp"), paymentPath)
        (sut, routes) = stub
        harnessed <- harness(settings, List(accounts, balance), routes, sut)
        pool <- UserPool.paged(repo, shard, UserPool.pageSize)
        busy <- BusyUsers.make
        lag <- ScheduleLag.make
        now <- Clock.instant
        horizon = now.plusMillis(400)
        rate = VaryingRate(20.0, horizon, instant => if instant.isBefore(horizon) then 20.0 else 0.0)
        loop = DriverLoop(
          ArrivalProcess.startingAt(now, RandomSource.seeded(31L)),
          rate,
          pool,
          busy,
          harnessed.runner,
          harnessed.recorder,
          lag,
          RandomSource.seeded(32L),
          64,
        )
        // The scope is the campaign's: leaving it interrupts whatever is still in flight, which
        // is why the sessions are drained inside it rather than after.
        _ <- ZIO.scoped(loop.run *> busy.size.repeatUntil(_ == 0).timeout(30.seconds))
        stored <- rows
        hops <- sut.paths
        head <- lag.headIntendedStart
        abort <- harnessed.recorder.abort
      yield assertTrue(
        // Every arrival ran a session, and every session belongs to a user of this shard.
        hops.count(_ == "GET /authorize") > 1,
        stored.nonEmpty,
        stored.forall(session => Set(20L, 21L, 22L).contains(session.userId)),
        // A drained queue reports no lag rather than the last arrival's, which would read as a
        // driver falling behind while it is idle.
        head.isEmpty,
        abort.isEmpty,
      )
    },
  ).provideSome[TransactorZIO](TestClient.layer, Scope.default) @@ TestAspect.sequential @@ TestAspect.withLiveClock
