package versola.user.move

import versola.oauth.AuthMetrics
import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import versola.user.move.UserMoveRepositorySpec.{appClient, mac, moveOne, now, otherClient, userOne}
import versola.util.UnitSpecBase
import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

/** The guard every request about one user asks (versolauth/versola#1051): one read by user id, and a refusal that says which. */
object UserDeparturesSpec extends UnitSpecBase:

  private val userId = UserId(userOne)

  private def departures(repository: UserDepartureRepository): ZIO[Scope, Nothing, UserDepartures] =
    UserDepartures.live.build.map(_.get[UserDepartures]).provide(ZLayer.succeed(repository), Scope.default)

  def spec = suite("UserDepartures")(
    test("a user with no departure is served, at the cost of one read by user id") {
      val repository = stub[UserDepartureRepository]
      for
        _ <- repository.find.succeedsWith(None)
        service <- departures(repository)
        result <- service.refuse(userId).either
      yield assertTrue(result.isRight, repository.find.calls == List(userId), repository.findToken.calls.isEmpty)
    },
    test("a leaving user is refused as leaving, and one who moved as moved, with the cell") {
      val leaving = stub[UserDepartureRepository]
      val moved = stub[UserDepartureRepository]
      for
        _ <-
          leaving.find.succeedsWith(Some(UserDeparture(userId = userId, toCell = "kz-2", moveId = moveOne, state = DepartureState.Leaving, at = now)))
        _ <- moved.find.succeedsWith(Some(UserDeparture(userId = userId, toCell = "kz-2", moveId = moveOne, state = DepartureState.Moved, at = now)))
        first <- departures(leaving).flatMap(_.refuse(userId).either)
        second <- departures(moved).flatMap(_.refuse(userId).either)
      yield assertTrue(first == Left(DepartureRefusal.Leaving), second == Left(DepartureRefusal.Moved("kz-2")))
    },
    test("a read that fails is a failure, not a user who is served") {
      val repository = stub[UserDepartureRepository]
      val failure = RuntimeException("db down")
      for
        _ <- repository.find.failsWith(failure)
        result <- departures(repository).flatMap(_.refuse(userId).either)
      yield assertTrue(result == Left(failure))
    },
    test("a token of a user who moved is refused as moved, with the cell; one that is not is served") {
      val moved = stub[UserDepartureRepository]
      val unknown = stub[UserDepartureRepository]
      for
        _ <- moved.findToken.succeedsWith(Some("kz-2"))
        _ <- unknown.findToken.succeedsWith(None)
        first <- departures(moved).flatMap(_.refuseToken(mac(1), appClient).either)
        second <- departures(unknown).flatMap(_.refuseToken(mac(1), appClient).either)
      yield assertTrue(first == Left(DepartureRefusal.Moved("kz-2")), second.isRight, moved.find.calls.isEmpty)
    },
    test("a token is asked for with the client that presents it, and a tombstone of another client is not found") {
      val repository = stub[UserDepartureRepository]
      for
        _ <- repository.findToken.succeedsWith(None)
        result <- departures(repository).flatMap(_.refuseToken(mac(1), otherClient).either)
      yield assertTrue(result.isRight, repository.findToken.calls.map(_._2) == List(otherClient))
    },
    test("the tombstones of expired tokens and the stale arrivals are removed in the background, and a failed run is tried again") {
      val repository = stub[UserDepartureRepository]
      val moves = stub[UserMoveRepository]
      for
        _ <- moves.removeStaleArrivals.succeedsWith(0)
        _ <- repository.removeExpiredTokens.failsWith(RuntimeException("db down"))
        _ <- repository.removeExpiredTokens.succeedsWith(0)
        _ <- ZIO.scoped(UserMoves.live.build.provideSome[Scope](
          ZLayer.succeed(versola.auth.TestEnvConfig.coreConfig),
          ZLayer.succeed(stub[versola.user.UserRepository]),
          ZLayer.succeed(repository),
          ZLayer.succeed(moves),
          ZLayer.succeed(stub[versola.oauth.handoff.CellEnvelope]),
          ZLayer.succeed(stub[versola.oauth.client.OAuthConfigurationService]),
          ZLayer.succeed[versola.util.audit.AuditEvents](versola.util.audit.AuditEvents.noop),
        ) *> (ZIO.yieldNow *> TestClock.adjust(UserMoves.CleanupEvery + 1.second) *> ZIO.yieldNow *> TestClock.adjust(UserMoves.CleanupEvery)))
      yield assertTrue(
        repository.removeExpiredTokens.calls.size >= 2,
        moves.removeStaleArrivals.calls.nonEmpty,
        // the cut-off is a day before the run (the test clock is within hours of its epoch), not the run's own time
        moves.removeStaleArrivals.calls.forall(cutOff => cutOff.isBefore(java.time.Instant.EPOCH)),
      )
    },
    test("an answer of moved is counted by the endpoint that gave it, and by no other label") {
      def counted(endpoint: String) = Metric.counter("auth_moved_answers_total").tagged(Set(MetricLabel("endpoint", endpoint))).value
      for
        before <- ZIO.foreach(List("token", "userinfo", "revoke", "introspect"))(endpoint => counted(endpoint).map(endpoint -> _.count)).map(_.toMap)
        _ <- AuthMetrics.movedAnswered("token")
        _ <- AuthMetrics.movedAnswered("token")
        _ <- AuthMetrics.movedAnswered("revoke")
        after <- ZIO.foreach(List("token", "userinfo", "revoke", "introspect"))(endpoint => counted(endpoint).map(endpoint -> _.count)).map(_.toMap)
      yield assertTrue(
        after("token") - before("token") == 2.0,
        after("revoke") - before("revoke") == 1.0,
        after("userinfo") == before("userinfo"),
        after("introspect") == before("introspect"),
      )
    },
  )
