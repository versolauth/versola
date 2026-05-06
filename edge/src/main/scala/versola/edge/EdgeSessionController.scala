package versola.edge

import versola.edge.model.{ClientId, EdgeSession, EdgeSessionId}
import versola.util.MAC
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.time.Instant

/**
 * Session management controller
 * Handles creating, retrieving, and deleting edge sessions
 */
object EdgeSessionController extends Controller:
  type Env = Tracing & EdgeSessionService

  def routes: Routes[Env, Nothing] = Routes(
    createSessionEndpoint,
    getSessionEndpoint,
    deleteSessionEndpoint,
    deleteSessionsByClientIdEndpoint,
  )

  val createSessionEndpoint =
    Method.POST / "v1" / "sessions" -> handler { (request: Request) =>
      (for
        sessionService <- ZIO.service[EdgeSessionService]
        
        // Parse request body
        createRequest <- request.body.asString
          .flatMap(body => ZIO.fromEither(body.fromJson[CreateSessionRequest]))
          .mapError(_ => SessionError.InvalidRequest)
        
        // Create session
        sessionId <- sessionService.createSession(
          createRequest.state,
        )
        
        // Return session ID
        response = CreateSessionResponse(
          sessionId = java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(sessionId),
        )
      yield Response.json(response.toJson).status(Status.Created))
        .catchAll {
          case error: SessionError => handleSessionError(error)
          case ex: Throwable =>
            ZIO.logErrorCause("Session operation error", Cause.fail(ex)) *>
              ZIO.succeed(Response.internalServerError)
        }
    }

  val getSessionEndpoint =
    Method.GET / "v1" / "sessions" / string("sessionId") -> handler { (sessionIdStr: String, request: Request) =>
      (for
        sessionService <- ZIO.service[EdgeSessionService]
        
        // Parse session ID
        sessionId <- parseSessionId(sessionIdStr)
        
        // Get session
        session <- sessionService.getSession(sessionId)
          .someOrFail(SessionError.SessionNotFound)
        
        // Return session details
        response = SessionResponse(
          clientId = session.clientId,
          state = session.state,
          tokenExpiresAt = session.tokenExpiresAt,
          createdAt = session.createdAt,
          sessionExpiresAt = session.sessionExpiresAt,
        )
      yield Response.json(response.toJson))
        .catchAll {
          case error: SessionError => handleSessionError(error)
          case ex: Throwable =>
            ZIO.logErrorCause("Session operation error", Cause.fail(ex)) *>
              ZIO.succeed(Response.internalServerError)
        }
    }

  val deleteSessionEndpoint =
    Method.DELETE / "v1" / "sessions" / string("sessionId") -> handler { (sessionIdStr: String, request: Request) =>
      (for
        sessionService <- ZIO.service[EdgeSessionService]

        // Parse session ID
        sessionId <- parseSessionId(sessionIdStr)

        // Delete session
        _ <- sessionService.deleteSession(sessionId)

        response = DeleteSessionResponse(success = true)
      yield Response.json(response.toJson))
        .catchAll {
          case error: SessionError => handleSessionError(error)
          case ex: Throwable =>
            ZIO.logErrorCause("Session operation error", Cause.fail(ex)) *>
              ZIO.succeed(Response.internalServerError)
        }
    }

  val deleteSessionsByClientIdEndpoint =
    Method.DELETE / "v1" / "sessions" / "client" / string("clientId") -> handler { (clientIdStr: String, request: Request) =>
      (for
        sessionService <- ZIO.service[EdgeSessionService]

        clientId = ClientId(clientIdStr)

        // Delete all sessions for this client
        _ <- sessionService.deleteSessionsByClientId(clientId)

        response = DeleteSessionResponse(success = true)
      yield Response.json(response.toJson))
        .catchAll {
          case error: SessionError => handleSessionError(error)
          case ex: Throwable =>
            ZIO.logErrorCause("Session operation error", Cause.fail(ex)) *>
              ZIO.succeed(Response.internalServerError)
        }
    }

  // Helper methods
  private def parseSessionId(sessionIdStr: String): IO[SessionError, MAC.Of[EdgeSessionId]] =
    ZIO.attempt(MAC(java.util.Base64.getUrlDecoder.decode(sessionIdStr)))
      .mapError(_ => SessionError.InvalidSessionId)

  private def handleSessionError(error: SessionError): UIO[Response] =
    error match
      case SessionError.SessionNotFound =>
        ZIO.logWarning("Session not found") *>
          ZIO.succeed:
            Response
              .json(SessionErrorResponse("session_not_found", "Session not found or expired").toJson)
              .status(Status.NotFound)
      
      case SessionError.InvalidSessionId =>
        ZIO.succeed:
          Response
            .json(SessionErrorResponse("invalid_session_id", "Invalid session ID format").toJson)
            .status(Status.BadRequest)
      
      case SessionError.InvalidRequest =>
        ZIO.succeed:
          Response
            .json(SessionErrorResponse("invalid_request", "Invalid request body").toJson)
            .status(Status.BadRequest)
      
      case ex: Throwable =>
        ZIO.logErrorCause("Session operation error", Cause.fail(ex)) *>
          ZIO.succeed(Response.internalServerError)

  // Request/Response models
  case class CreateSessionRequest(
      state: Option[String],
  ) derives JsonCodec

  case class CreateSessionResponse(
      @jsonField("session_id") sessionId: String,
  ) derives JsonCodec

  given JsonCodec[ClientId] = JsonCodec.string.transform(ClientId(_), identity[String])

  case class SessionResponse(
      @jsonField("client_id") clientId: ClientId,
      state: Option[String],
      @jsonField("token_expires_at") tokenExpiresAt: Instant,
      @jsonField("created_at") createdAt: Instant,
      @jsonField("session_expires_at") sessionExpiresAt: Instant,
  ) derives JsonCodec

  case class DeleteSessionResponse(
      success: Boolean,
  ) derives JsonCodec

  case class SessionErrorResponse(
      error: String,
      @jsonField("error_description") errorDescription: String,
  ) derives JsonCodec

  // Error types
  sealed trait SessionError extends Throwable

  object SessionError:
    case object SessionNotFound extends SessionError
    case object InvalidSessionId extends SessionError
    case object InvalidRequest extends SessionError

