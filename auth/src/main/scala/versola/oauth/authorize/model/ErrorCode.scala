package versola.oauth.authorize.model

private[authorize]
type ErrorCode = ErrorCode.Type

private[authorize]
object ErrorCode:
  opaque type Type <: String = String

  val InvalidRequest: ErrorCode = "invalid_request"
  val UnsupportedResponseType: ErrorCode = "unsupported_response_type"
  val UnauthorizedClient: ErrorCode = "unauthorized_client"
  val InvalidScope: ErrorCode = "invalid_scope"
  val LoginRequired: ErrorCode = "login_required"
  val AccessDenied: ErrorCode = "access_denied"

