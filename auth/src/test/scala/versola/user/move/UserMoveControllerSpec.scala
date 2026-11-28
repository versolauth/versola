package versola.user.move

import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.handoff.CellEnvelope
import versola.user.model.UserId
import versola.util.http.{NoopTracing, Observability}
import versola.util.{JWT, RsaKeyPair}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The internal endpoints a move is driven through (versolauth/versola#1051): the paths, the shapes, the statuses, and that none is
  * served without central's token. What a step does is in [[UserMovesSpec]]. */
object UserMoveControllerSpec extends ZIOSpecDefault, ZIOStubs:

  private val userId = UserId(UUID.fromString("0198e5a0-0000-7000-8000-000000000001"))

  private def token(key: RsaKeyPair): Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = TestEnvConfig.signedBy(key),
    )

  /** The one call: `service` behind the routes, `authorization` as the token the request carries (none for no header). */
  private def call(
      request: Request,
      service: UserMoves,
      key: Option[RsaKeyPair] = Some(TestEnvConfig.centralKeyPair),
  ): ZIO[Client & TestClient & Scope, Throwable, Response] =
    for
      client <- ZIO.service[Client]
      tracing <- NoopTracing.layer.build
      bearer <- ZIO.foreach(key)(token)
      _ <- TestClient.addRoutes(
        Observability.handleErrors(
          UserMoveController.routes.provideEnvironment(
            ZEnvironment[UserMoves](service) ++ ZEnvironment(TestEnvConfig.coreConfig) ++ tracing,
          ),
        ),
      )
      response <- client.batched(bearer.fold(request)(value => request.addHeader(Header.Authorization.Bearer(value))))
    yield response

  private def body(response: Response): Task[String] = response.body.asString

  private def post(path: String, json: String) =
    Request.post(URL.decode(path).toOption.get, Body.fromString(json)).addHeader(Header.ContentType(MediaType.application.json))

  private def refusing(error: MoveError) = ZIO.fail(error)

  private val move = "0198e5a0-0000-7000-8000-0000000000b1"
  private val moveId = UUID.fromString(move)
  private val begun = s"""{"userId":"$userId","to":"kz-2","moveId":"$move"}"""
  private val arrived = s"""{"envelope":"v1.kz-2.abc","userId":"$userId","from":"kz-1","moveId":"$move"}"""

  def spec = suite("UserMoveController")(
    suite("authentication")(
      test("no endpoint is served without central's token, or with one signed by anybody else") {
        val service = stub[UserMoves]
        val requests = List(
          post("/users/departures", begun),
          Request.get(URL.decode(s"/users/departures/export?userId=$userId").toOption.get),
          post("/users/departures/complete", s"""{"userId":"$userId","moveId":"$move","digest":"d"}"""),
          Request.delete(URL.decode(s"/users/departures?userId=$userId").toOption.get),
          Request.get(URL.decode("/users/departures/summary").toOption.get),
          post("/users/arrivals", arrived),
          post("/users/arrivals/keep", s"""{"userId":"$userId","moveId":"$move"}"""),
          Request.delete(URL.decode(s"/users/arrivals?userId=$userId&moveId=$move").toOption.get),
        )
        for
          without <- ZIO.foreach(requests)(call(_, service, key = None))
          wrong <- ZIO.foreach(requests)(call(_, service, key = Some(TestEnvConfig.impostorKeyPair)))
        yield assertTrue(
          without.forall(_.status == Status.Unauthorized),
          wrong.forall(_.status == Status.Unauthorized),
          service.begin.calls.isEmpty,
          service.exportUser.calls.isEmpty,
          service.complete.calls.isEmpty,
          service.cancel.calls.isEmpty,
          service.arrive.calls.isEmpty,
          service.keep.calls.isEmpty,
          service.forgetArrival.calls.isEmpty,
        )
      },
    ),
    suite("POST /users/departures")(
      test("is 204 and passes the user and the cell") {
        val service = stub[UserMoves]
        for
          _ = service.begin.returnsWith(ZIO.unit)
          response <- call(post("/users/departures", begun), service)
        yield assertTrue(response.status == Status.NoContent, service.begin.calls == List((userId, "kz-2", moveId)))
      },
      test("is 404 for a user this cell does not hold, 409 for a user going elsewhere, 400 for a cell that is not one") {
        val service = stub[UserMoves]
        for
          _ = service.begin.returnsWith(refusing(MoveError.UserNotFound))
          notFound <- call(post("/users/departures", begun), service)
          notFoundBody <- body(notFound)
          _ = service.begin.returnsWith(refusing(MoveError.DepartureExists))
          conflict <- call(post("/users/departures", begun), service)
          conflictBody <- body(conflict)
          _ = service.begin.returnsWith(refusing(MoveError.InvalidCell))
          invalid <- call(post("/users/departures", begun), service)
        yield assertTrue(
          notFound.status == Status.NotFound,
          notFoundBody == """{"error":"user_not_found"}""",
          conflict.status == Status.Conflict,
          conflictBody == """{"error":"departure_exists"}""",
          invalid.status == Status.BadRequest,
        )
      },
      test("is 400 for a departure with no move id, and nothing is begun") {
        val service = stub[UserMoves]
        for response <- call(post("/users/departures", s"""{"userId":"$userId","to":"kz-2"}"""), service)
        yield assertTrue(response.status == Status.BadRequest, service.begin.calls.isEmpty)
      },
      test("is 400 for a body that is not a departure, and nothing is begun") {
        val service = stub[UserMoves]
        for
          response <- call(post("/users/departures", s"""{"userId":"not-a-uuid","to":"kz-2","moveId":"$move"}"""), service)
        yield assertTrue(response.status == Status.BadRequest, service.begin.calls.isEmpty)
      },
    ),
    suite("GET /users/departures/export")(
      test("is 200 with the envelope and the digest, and only those") {
        val service = stub[UserMoves]
        for
          _ = service.exportUser.returnsWith(ZIO.succeed(UserMoves.Exported(envelope = "v1.kz-2.abc", digest = "dig")))
          response <- call(Request.get(URL.decode(s"/users/departures/export?userId=$userId").toOption.get), service)
          text <- body(response)
        yield assertTrue(
          response.status == Status.Ok,
          text == """{"envelope":"v1.kz-2.abc","digest":"dig"}""",
          service.exportUser.calls == List(userId),
        )
      },
      test("is 409 for a user who is not leaving, and 503 while this cell has no key for the cell the user is going to") {
        val service = stub[UserMoves]
        val url = URL.decode(s"/users/departures/export?userId=$userId").toOption.get
        for
          _ = service.exportUser.returnsWith(refusing(MoveError.NotLeaving))
          notLeaving <- call(Request.get(url), service)
          notLeavingBody <- body(notLeaving)
          _ = service.exportUser.returnsWith(refusing(MoveError.NoCellKey))
          noKey <- call(Request.get(url), service)
        yield assertTrue(
          notLeaving.status == Status.Conflict,
          notLeavingBody == """{"error":"not_leaving"}""",
          noKey.status == Status.ServiceUnavailable,
        )
      },
    ),
    suite("POST /users/departures/complete")(
      test("is 204 with the move and the digest handed on, and 409 for a user who is not leaving") {
        val completed = s"""{"userId":"$userId","moveId":"$move","digest":"d"}"""
        val service = stub[UserMoves]
        for
          _ = service.complete.returnsWith(ZIO.unit)
          done <- call(post("/users/departures/complete", completed), service)
          _ = service.complete.returnsWith(refusing(MoveError.NotLeaving))
          refused <- call(post("/users/departures/complete", completed), service)
          noDigest <- call(post("/users/departures/complete", s"""{"userId":"$userId","moveId":"$move"}"""), service)
        yield assertTrue(
          done.status == Status.NoContent,
          refused.status == Status.Conflict,
          noDigest.status == Status.BadRequest,
          service.complete.calls == List((userId, moveId, "d"), (userId, moveId, "d")),
        )
      },
    ),
    suite("DELETE /users/departures")(
      test("is 204, and 409 user_moved for a user who has moved") {
        val service = stub[UserMoves]
        val url = URL.decode(s"/users/departures?userId=$userId").toOption.get
        for
          _ = service.cancel.returnsWith(ZIO.unit)
          done <- call(Request.delete(url), service)
          _ = service.cancel.returnsWith(refusing(MoveError.UserMoved))
          refused <- call(Request.delete(url), service)
          refusedBody <- body(refused)
        yield assertTrue(done.status == Status.NoContent, refused.status == Status.Conflict, refusedBody == """{"error":"user_moved"}""")
      },
    ),
    suite("GET /users/departures/summary")(
      test("says how many are leaving and moved, how many tokens answer moved, and until when") {
        val service = stub[UserMoves]
        for
          _ = service.summary.returnsWith(
            ZIO.succeed(DepartureSummary(leaving = 1, moved = 2, tokens = 3, tokensUntil = Some(Instant.parse("2026-12-01T00:00:00Z")))),
          )
          full <- call(Request.get(URL.decode("/users/departures/summary").toOption.get), service)
          fullBody <- body(full)
          _ = service.summary.returnsWith(ZIO.succeed(DepartureSummary(leaving = 0, moved = 0, tokens = 0, tokensUntil = None)))
          empty <- call(Request.get(URL.decode("/users/departures/summary").toOption.get), service)
          emptyBody <- body(empty)
        yield assertTrue(
          full.status == Status.Ok,
          fullBody == """{"leaving":1,"moved":2,"tokens":3,"tokensUntil":"2026-12-01T00:00:00Z"}""",
          emptyBody == """{"leaving":0,"moved":0,"tokens":0,"tokensUntil":null}""",
        )
      },
    ),
    suite("POST /users/arrivals")(
      test("is 204 and hands the envelope on as it came") {
        val service = stub[UserMoves]
        for
          _ = service.arrive.returnsWith(ZIO.unit)
          response <- call(post("/users/arrivals", arrived), service)
          noMove <- call(post("/users/arrivals", s"""{"envelope":"x","userId":"$userId","from":"kz-1"}"""), service)
        yield assertTrue(
          response.status == Status.NoContent,
          noMove.status == Status.BadRequest,
          service.arrive.calls == List(("v1.kz-2.abc", userId, "kz-1", moveId)),
        )
      },
      test("is 409 user_exists for a user or an identifier held here") {
        val service = stub[UserMoves]
        for
          _ = service.arrive.returnsWith(refusing(MoveError.UserExists))
          response <- call(post("/users/arrivals", arrived), service)
          text <- body(response)
        yield assertTrue(response.status == Status.Conflict, text == """{"error":"user_exists"}""")
      },
      test("is 400 for an envelope that is not for this cell, not of this use, forged or expired, and for a document it does not take") {
        val service = stub[UserMoves]
        val refused = List(
          MoveError.Envelope(CellEnvelope.Refused.Elsewhere) -> "envelope_elsewhere",
          MoveError.Envelope(CellEnvelope.Refused.WrongPurpose) -> "envelope_purpose",
          MoveError.Envelope(CellEnvelope.Refused.Forged) -> "envelope_forged",
          MoveError.Envelope(CellEnvelope.Refused.Expired) -> "envelope_expired",
          MoveError.Document(UserMoveDocument.DocumentError.UnsupportedVersion(2)) -> "unsupported_version",
          MoveError.Document(UserMoveDocument.DocumentError.Invalid) -> "invalid_document",
          MoveError.Document(UserMoveDocument.DocumentError.NotInConfiguration("token_scope")) -> "invalid_document",
          MoveError.EnvelopeOtherCell -> "envelope_other_cell",
          MoveError.EnvelopeOtherUser -> "envelope_other_user",
          MoveError.EnvelopeOtherMove -> "envelope_other_move",
        )
        for
          answers <- ZIO.foreach(refused): (error, code) =>
            service.arrive.returnsWith(refusing(error))
            call(post("/users/arrivals", arrived), service).flatMap(response => body(response).map(text => (response.status, text, code)))
        yield assertTrue(answers.forall((status, text, code) => status == Status.BadRequest && text == s"""{"error":"$code"}"""))
      },
    ),
    suite("POST /users/arrivals (size)")(
      test("is 413 for a request over the cap, by its declared length and by what it carries, and nothing is parsed or arrived") {
        val service = stub[UserMoves]
        val padding = "x" * (UserMoveController.MaxArrivalBody + 1)
        for
          response <- call(post("/users/arrivals", s"""{"envelope":"$padding","userId":"$userId","from":"kz-1","moveId":"$move"}"""), service)
          text <- body(response)
        yield assertTrue(response.status == Status.RequestEntityTooLarge, text == """{"error":"too_large"}""", service.arrive.calls.isEmpty)
      },
    ),
    suite("POST /users/arrivals/keep")(
      test("is 204, and 409 no_arrival when this cell has no arrival of that move") {
        val service = stub[UserMoves]
        val keep = s"""{"userId":"$userId","moveId":"$move"}"""
        for
          _ = service.keep.returnsWith(ZIO.unit)
          done <- call(post("/users/arrivals/keep", keep), service)
          _ = service.keep.returnsWith(refusing(MoveError.NoArrival))
          refused <- call(post("/users/arrivals/keep", keep), service)
          refusedBody <- body(refused)
          bad <- call(post("/users/arrivals/keep", s"""{"userId":"$userId"}"""), service)
        yield assertTrue(
          done.status == Status.NoContent,
          refused.status == Status.Conflict,
          refusedBody == """{"error":"no_arrival"}""",
          bad.status == Status.BadRequest,
          service.keep.calls == List((userId, moveId), (userId, moveId)),
        )
      },
    ),
    suite("DELETE /users/arrivals")(
      test("is 204, also when there is nothing to remove") {
        val service = stub[UserMoves]
        for
          _ = service.forgetArrival.returnsWith(ZIO.unit)
          response <- call(Request.delete(URL.decode(s"/users/arrivals?userId=$userId&moveId=$move").toOption.get), service)
          bad <- call(Request.delete(URL.decode(s"/users/arrivals?userId=nobody&moveId=$move").toOption.get), service)
          noMove <- call(Request.delete(URL.decode(s"/users/arrivals?userId=$userId").toOption.get), service)
        yield assertTrue(
          response.status == Status.NoContent,
          service.forgetArrival.calls == List((userId, moveId)),
          bad.status == Status.BadRequest,
          noMove.status == Status.BadRequest,
        )
      },
    ),
  ).provideSome[Scope](TestClient.layer) @@ TestAspect.silentLogging
