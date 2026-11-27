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

  /** RFC 8705 §6.5: the certificate the tenant's proxy forwarded could not be read. Answered
    * as `invalid_client` because that is what it costs the request, but `reason` — which
    * describes the deployment's proxy, not the caller — is logged rather than returned. */
  case class InvalidClientCertificate(reason: String) extends IntrospectionError:
    val status = Status.Unauthorized
    val error = "invalid_client"
    val errorDescription = InvalidClient.errorDescription

  /** The user is moving to another cell (versolauth/versola#1051): `503` with `Retry-After`. */
  case object Unavailable extends IntrospectionError:
    val status = Status.ServiceUnavailable
    val error = "temporarily_unavailable"
    val errorDescription = Some("The server is temporarily unable to handle the request")

  /** The user, or the refresh token, went to `cell`: `421`; the body, which names the cell, is the controller's. */
  case class Moved(cell: String) extends IntrospectionError:
    val status = Status.MisdirectedRequest
    val error = "moved"
    val errorDescription = None
