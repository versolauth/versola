package versola.edge

import versola.edge.model.EdgeSessionId
import versola.oauth.client.model.ClientId
import versola.util.MAC
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * OAuth completion endpoint controller
 * Handles OAuth provider redirects with authorization codes
 */
object CompleteController extends Controller:
  type Env = Tracing & EdgeSessionService

  def routes: Routes[Env, Nothing] = Routes(
    completeEndpoint,
  )

  // Request model for query parameters
  case class CompleteRequest(
    code: String,
    state: String,
    clientId: ClientId
  )

  val completeEndpoint =
    Method.GET / "complete" -> handler { (request: Request) =>
      (for
        sessionService <- ZIO.service[EdgeSessionService]

        // Extract query parameters
        params = request.url.queryParams.map
        code <- ZIO.fromOption(params.get("code").flatMap(_.headOption))
          .orElseFail(CompleteError.MissingParameter("code"))
        state <- ZIO.fromOption(params.get("state").flatMap(_.headOption))
          .orElseFail(CompleteError.MissingParameter("state"))
        clientIdStr <- ZIO.fromOption(params.get("client_id").flatMap(_.headOption))
          .orElseFail(CompleteError.MissingParameter("client_id"))

        clientId = ClientId(clientIdStr)

        // Create the request object
        req = CompleteRequest(code, state, clientId)

        // Create session with tokens (exchange code for tokens)
        sessionId <- sessionService.createSessionWithTokens(
          state = Some(req.state),
          code = req.code,
          clientId = req.clientId,
        ).mapError(ex => CompleteError.TokenExchangeFailed(ex.getMessage))

        // Return success response
        response = CompleteResponse(
          success = true,
          sessionId = java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(sessionId),
        )
      yield Response.json(response.toJson))
        .catchAll {
          case error: CompleteError =>
            ZIO.logWarning(s"Complete error: ${error.message}") *>
              ZIO.succeed:
                val errorResponse = CompleteErrorResponse(
                  success = false,
                  error = error.code,
                  errorDescription = error.message,
                )
                Response
                  .json(errorResponse.toJson)
                  .status(error.status)

          case ex: Throwable =>
            ZIO.logErrorCause("Complete error", Cause.fail(ex)) *>
              ZIO.succeed(Response.internalServerError)
        }
    }

  // Response models
  case class CompleteResponse(
      success: Boolean,
      sessionId: String,
  ) derives JsonCodec

  case class CompleteErrorResponse(
      success: Boolean,
      error: String,
      @jsonField("error_description") errorDescription: String,
  ) derives JsonCodec

  // Error types
  sealed trait CompleteError:
    def code: String
    def message: String
    def status: Status

  object CompleteError:
    case class MissingParameter(param: String) extends CompleteError:
      def code = "missing_parameter"
      def message = s"Missing required parameter: $param"
      def status = Status.BadRequest

    case object InvalidState extends CompleteError:
      def code = "invalid_state"
      def message = "Invalid state parameter"
      def status = Status.BadRequest

    case object SessionNotFound extends CompleteError:
      def code = "session_not_found"
      def message = "Session not found or expired"
      def status = Status.NotFound

    case object StateMismatch extends CompleteError:
      def code = "state_mismatch"
      def message = "State parameter does not match session"
      def status = Status.BadRequest

    case class TokenExchangeFailed(reason: String) extends CompleteError:
      def code = "token_exchange_failed"
      def message = s"Failed to exchange authorization code: $reason"
      def status = Status.BadRequest

