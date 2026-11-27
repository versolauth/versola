package versola.user.move

import versola.user.authorizeInternal
import versola.user.model.UserId
import versola.util.CoreConfig
import versola.util.http.{BadRequest, Controller, Observability}
import zio.*
import zio.http.{Header, Method, Request, Response, Routes, Status, handler}
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.nio.charset.StandardCharsets
import java.util.UUID

/** The internal endpoints central moves a user between cells with (versolauth/versola#1051); central's token, as every internal
  * endpoint. All idempotent: a step whose answer was lost is repeated.
  *
  * A refusal is the status below and `{"error":"<code>"}`, the code one of [[MoveError]]'s.
  */
object UserMoveController extends Controller:
  type Env = Tracing & UserMoves & CoreConfig

  /** `{userId, to, moveId}`. */
  final case class DepartureRequest(userId: UserId, to: String, moveId: UUID) derives JsonCodec

  /** `{userId, moveId, digest}`: the digest is the one of the last export. */
  final case class CompleteRequest(userId: UserId, moveId: UUID, digest: String) derives JsonCodec

  /** `{"envelope": "<sealed for the cell the departure names>", "digest": "..."}`: what the export answers. */
  final case class ExportBody(envelope: String, digest: String) derives JsonCodec

  /** `{envelope, userId, from, moveId}`: what central asks an arrival with; the envelope is bound to all three. */
  final case class ArrivalRequest(envelope: String, userId: UserId, from: String, moveId: UUID) derives JsonCodec

  /** `{userId, moveId}`. */
  final case class KeepRequest(userId: UserId, moveId: UUID) derives JsonCodec

  /** The most an arrival's request may weigh: the document (1 MiB) as base64, sealed, with some room for the rest. */
  val MaxArrivalBody = 3 * 1024 * 1024

  def routes: Routes[Env, Throwable] = Routes(
    beginEndpoint,
    exportEndpoint,
    completeEndpoint,
    cancelEndpoint,
    summaryEndpoint,
    arriveEndpoint,
    keepEndpoint,
    forgetArrivalEndpoint,
  )

  private def status(error: MoveError): Status = error match
    case MoveError.NotACell | MoveError.InvalidCell | MoveError.Envelope(_) | MoveError.Document(_) | MoveError.EnvelopeOtherCell |
        MoveError.EnvelopeOtherUser | MoveError.EnvelopeOtherMove =>
      Status.BadRequest
    case MoveError.UserNotFound => Status.NotFound
    case MoveError.DepartureExists | MoveError.NotLeaving | MoveError.UserMoved | MoveError.UserExists | MoveError.NoArrival => Status.Conflict
    case MoveError.TooLarge => Status.RequestEntityTooLarge
    case MoveError.NoCellKey => Status.ServiceUnavailable

  private def refused(error: MoveError): Response =
    Response.json(Json.Obj("error" -> Json.Str(error.code)).toJson).status(status(error))

  /** The error is already in the request's error context ([[UserMoves]] set it where it decided); only the answer is made here. */
  private def answered(effect: ZIO[Env, Throwable | MoveError, Response]): ZIO[Env, Throwable, Response] =
    effect.catchAll:
      case error: MoveError => ZIO.succeed(refused(error))
      case failure: Throwable => ZIO.fail(failure)

  val beginEndpoint =
    Method.POST / "users" / "departures" -> handler { (request: Request) =>
      answered:
        for
          _ <- authorizeInternal(request)
          body <- request.bodyAs[DepartureRequest]
          _ <- ZIO.serviceWithZIO[UserMoves](_.begin(body.userId, body.to, body.moveId))
        yield Response.status(Status.NoContent)
    }

  val exportEndpoint =
    Method.GET / "users" / "departures" / "export" -> handler { (request: Request) =>
      answered:
        for
          _ <- authorizeInternal(request)
          userId <- request.url.queryZIO[UserId]("userId")
          exported <- ZIO.serviceWithZIO[UserMoves](_.exportUser(userId))
        yield Response.json(ExportBody(envelope = exported.envelope, digest = exported.digest).toJson)
    }

  val completeEndpoint =
    Method.POST / "users" / "departures" / "complete" -> handler { (request: Request) =>
      answered:
        for
          _ <- authorizeInternal(request)
          body <- request.bodyAs[CompleteRequest]
          _ <- ZIO.serviceWithZIO[UserMoves](_.complete(body.userId, body.moveId, body.digest))
        yield Response.status(Status.NoContent)
    }

  val cancelEndpoint =
    Method.DELETE / "users" / "departures" -> handler { (request: Request) =>
      answered:
        for
          _ <- authorizeInternal(request)
          userId <- request.url.queryZIO[UserId]("userId")
          _ <- ZIO.serviceWithZIO[UserMoves](_.cancel(userId))
        yield Response.status(Status.NoContent)
    }

  val summaryEndpoint =
    Method.GET / "users" / "departures" / "summary" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        summary <- ZIO.serviceWithZIO[UserMoves](_.summary)
      yield Response.json(summary.toJson)
    }

  /** The body is read as a stream bounded by [[MaxArrivalBody]], so a request that understates or omits its length cannot be
    * buffered in full, and is `413` before it is parsed. */
  private def arrivalRequest(request: Request): ZIO[Any, Throwable | MoveError, ArrivalRequest] =
    val declared = request.body.knownContentLength
    for
      _ <- tooLarge.when(declared.exists(_ > MaxArrivalBody))
      bytes <- request.body.asStream.take(MaxArrivalBody.toLong + 1).runCollect
      _ <- tooLarge.when(bytes.length > MaxArrivalBody)
      body <- ZIO.fromEither(String(bytes.toArray, StandardCharsets.UTF_8).fromJson[ArrivalRequest])
        .mapError(error => BadRequest(error))
    yield body

  private def tooLarge: ZIO[Any, MoveError, Nothing] = Observability.setError(MoveError.TooLarge.code) *> ZIO.fail(MoveError.TooLarge)

  val arriveEndpoint =
    Method.POST / "users" / "arrivals" -> handler { (request: Request) =>
      answered:
        for
          _ <- authorizeInternal(request)
          body <- arrivalRequest(request)
          _ <- ZIO.serviceWithZIO[UserMoves](_.arrive(body.envelope, body.userId, body.from, body.moveId))
        yield Response.status(Status.NoContent)
    }

  val keepEndpoint =
    Method.POST / "users" / "arrivals" / "keep" -> handler { (request: Request) =>
      answered:
        for
          _ <- authorizeInternal(request)
          body <- request.bodyAs[KeepRequest]
          _ <- ZIO.serviceWithZIO[UserMoves](_.keep(body.userId, body.moveId))
        yield Response.status(Status.NoContent)
    }

  val forgetArrivalEndpoint =
    Method.DELETE / "users" / "arrivals" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        userId <- request.url.queryZIO[UserId]("userId")
        moveId <- request.url.queryZIO[UUID]("moveId")
        _ <- ZIO.serviceWithZIO[UserMoves](_.forgetArrival(userId, moveId))
      yield Response.status(Status.NoContent)
    }
