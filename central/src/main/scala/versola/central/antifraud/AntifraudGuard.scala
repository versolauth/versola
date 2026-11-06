package versola.central.antifraud

import versola.util.http.{BadRequest, EntityVersion, Observability}
import zio.*
import zio.http.{Header, Response, Status}

/** How an admin endpoint answers what auth refused, for all of them alike: the usual mechanism for a 400 (a [[BadRequest]]
  * that `Observability.handleErrors` turns into the answer, and the outcome in the request's error context), and the
  * status itself for the others.
  *
  *  - `Invalid`: 400, auth's reason as the text
  *  - `Forbidden`: 403, the caller's scope does not cover it
  *  - `NotFound`: 404
  *  - `StaleVersion`: 412, with the version the settings are at now as the `ETag`, when auth said it
  *
  * @param invalid  the error context's name for a 400
  * @param notFound the error context's name for a 404
  */
private[antifraud] object AntifraudGuard:

  def apply(invalid: String, notFound: String)(effect: IO[AntifraudListError | Throwable, Response]): Task[Response] =
    effect.catchAll:
      case AntifraudListError.Invalid(message) => Observability.setError(invalid, Some(message)) *> ZIO.fail(BadRequest(message))
      case AntifraudListError.NotFound => Observability.setError(notFound).as(Response.status(Status.NotFound))
      case AntifraudListError.Forbidden(message) =>
        Observability.setError("antifraud_forbidden", Some(message)).as(Response.text(message).status(Status.Forbidden))
      case AntifraudListError.StaleVersion(message, current) =>
        Observability.setError("antifraud_settings_version_mismatch", Some(message)).as(
          current.fold(Response.text(message).status(Status.PreconditionFailed))(version =>
            Response.text(message).status(Status.PreconditionFailed).addHeader(Header.Custom("ETag", EntityVersion.etag(version))),
          ),
        )
      case error: Throwable => ZIO.fail(error)
