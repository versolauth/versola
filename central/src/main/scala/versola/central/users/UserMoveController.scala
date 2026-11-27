package versola.central.users

import versola.central.audit.{Audit, AuditedController, audited}
import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.topology.CellId
import versola.util.PageLimit
import versola.util.http.Observability
import zio.http.{Method, Request, Response, Status, handler}
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.{UIO, ZIO}

/** Moving a user between cells, as an administrator asks for it (versolauth/versola#1051). */
object UserMoveController extends AuditedController:
  type Env = Tracing & UserMoveService & ResourceService

  def endpoints = List(startMoveEndpoint, listMovesEndpoint)

  /** Starts the move, which is done in the background: `202`. The refusals are the codes of [[MoveRefusal]]. */
  val startMoveEndpoint =
    (Method.POST / "users" / "move").audited(Audit.user("user", Audit.action("move"))) -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        body <- request.bodyAs[StartUserMoveRequest]
        _ <- Audit.target(body.userId)
        _ <- Observability.setUserId(body.userId.toString)
        _ <- ZIO.serviceWithZIO[UserMoveService](_.start(body.userId, body.cell))
      yield Response.status(Status.Accepted))
        .catchAll:
          case refusal: MoveRefusal => refused(refusal)
          case error: Throwable => ZIO.fail(error)
    }

  /** The moves of a cell (`cell`: from it or to it) and of a phase, newest first. */
  val listMovesEndpoint =
    (Method.GET / "users" / "moves").audited(Audit.Unrecorded) -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        cell <- request.queryZIO[Option[CellId]]("cell")
        phase <- request.queryZIO[Option[MovePhase]]("phase")
        limit <- request.queryZIO[Option[PageLimit]]("limit")
        moves <- ZIO.serviceWithZIO[UserMoveService](_.list(cell, phase, limit.getOrElse(PageLimit.Default)))
      yield Response.json(UserMovesResponse(moves.map(UserMoveResponse.of)).toJson)
    }

  private def refused(refusal: MoveRefusal): UIO[Response] =
    val status = refusal match
      case MoveRefusal.NoSuchUser => Status.NotFound
      case MoveRefusal.InProgress => Status.Conflict
      case _ => Status.BadRequest
    Observability.setError(refusal.code, Some(refusal.message)).as(Response.text(refusal.message).status(status))
