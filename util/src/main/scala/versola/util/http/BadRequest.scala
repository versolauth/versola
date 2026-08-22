package versola.util.http

import scala.util.control.NoStackTrace

/** A request body or parameter failed to decode or validate. Carries the failure message so
  * [[Observability.handleErrors]] can surface it in the 400 response instead of the generic
  * 500 that an unclassified `Throwable` gets. */
case class BadRequest(message: String) extends RuntimeException(message), NoStackTrace
