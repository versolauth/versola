package versola.audit

import versola.util.audit.{AuditDraft, AuditEvent, AuditEvents, AuditUnavailableException}
import versola.util.http.Observability
import versola.util.http.Observability.AuthDetails
import zio.*
import zio.json.ast.Json
import zio.logging.logContext

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** What the call sites of the audit log share. */
object AuditSupport:

  extension (events: AuditEvents)
    /** A state change whose event is inserted in the change's own transaction: the event is built before (`change` hands it to the
      * repository), and counted once the repository has returned, which is after the commit. A failure of the insert is a failure of
      * `change`, which then did not happen. */
    def atomically[R, E, A](draft: AuditDraft)(change: AuditEvent => ZIO[R, E, A]): ZIO[R, E, A] =
      atomicallyWhen(draft)(change)(_ => true)

    /** [[atomically]] for a change that writes its event only when what it returns says the change happened (`written`), so that an
      * event that was not inserted is not counted either. */
    def atomicallyWhen[R, E, A](draft: AuditDraft)(change: AuditEvent => ZIO[R, E, A])(written: A => Boolean): ZIO[R, E, A] =
      events.build(draft).flatMap(event => change(event).tap(result => events.committed(event).when(written(result))))

    /** [[atomically]] for a change that has no event when its feature switches the event off. */
    def atomicallyIf[R, E, A](enabled: Boolean, draft: AuditDraft)(change: Option[AuditEvent] => ZIO[R, E, A]): ZIO[R, E, A] =
      if enabled then events.build(draft).flatMap(event => change(Some(event)).tap(_ => events.committed(event)))
      else change(None)

    /** `record`, with the refusal as the failure the global handler answers with 503 `audit_unavailable`: the endpoint maps nothing by
      * hand. Under `overflow = drop` it cannot fail. */
    def log(draft: AuditDraft): IO[AuditUnavailableException.type, Unit] =
      events.record(draft).mapError(_ => AuditUnavailableException)

  /** What a repository does to the event the service built before the change, once it knows what the change was: a count, the
    * account it concerned, the session it ended. The event is still the service's; these only fill what the service could not know. */
  extension (event: AuditEvent)
    /** `name` is set to `value`, replacing a field of that name the details already have. */
    def withDetail(name: String, value: Json): AuditEvent =
      event.copy(details = Json.Obj(event.details.fields.filterNot(_._1 == name) :+ (name -> value)))

    def withCount(name: String, value: Int): AuditEvent = withDetail(name, Json.Num(value))

    def forUser(userId: UUID): AuditEvent = event.copy(userId = Some(userId))

    /** One copy of a template per session a statement ended. Each is its own row: the id is derived from the template's and the
      * session's, so that a repository needs no source of ids inside a transaction, and the same session of the same template
      * is the same row. */
    def forSession(publicId: String, userId: UUID, clients: Int): AuditEvent =
      event.copy(id = UUID.nameUUIDFromBytes(s"${event.id}/$publicId".getBytes(UTF_8)), sessionId = Some(publicId), userId = Some(userId))
        .withCount("clients", clients)

  /** The `auth` log context of the request being handled: the client, user, session and the refresh-token prefixes the call sites
    * below have already told `Observability`. */
  val currentAuth: UIO[AuthDetails] =
    logContext.get.map(_.get(Observability.auth).getOrElse(AuthDetails.empty))
