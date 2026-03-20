package versola.oauth.introspect.model

import zio.http.Status

sealed trait IntrospectionError:
  def status: Status
  def error: String
  def errorDescription: Option[String]

object IntrospectionError:
  case object InvalidRequest extends IntrospectionError:
    val status = Status.BadRequest
    val error = "invalid_request"
    val errorDescription = Some("Request is missing a required parameter or is otherwise malformed")

  case object InvalidClient extends IntrospectionError:
    val status = Status.Unauthorized
    val error = "invalid_client"
    val errorDescription = Some("Client authentication failed")

  case object Unauthenticated extends IntrospectionError:
    val status = Status.Unauthorized
    val error = "invalid_client"
    val errorDescription = Some("Client is not authorized to introspect this token")
