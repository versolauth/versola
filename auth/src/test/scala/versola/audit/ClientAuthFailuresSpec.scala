package versola.audit

import versola.util.UnitSpecBase
import versola.util.audit.{AuditDraft, AuditEvent, AuditEvents, AuditOutcome, AuditUnavailable}
import versola.util.http.Observability
import zio.*
import zio.test.*

/** `client.auth_failed` is coalesced: a failure is counted in memory and the counts are written once a minute, one event per
  * (client, address, reason), carrying `count`. Under a flood of guesses that is a row a minute, not a row a request. */
object ClientAuthFailuresSpec extends UnitSpecBase:

  /** What the writer was given, with the address and client the request context named at that moment: the flush runs in no request's
    * fiber, and the event is filled in from the context it sets up. */
  private case class Seen(draft: AuditDraft, ip: Option[String], clientId: Option[String])

  private final class Probe(seen: Ref[List[Seen]]) extends AuditEvents:
    private def note(draft: AuditDraft) =
      for
        ip <- Observability.currentIp
        auth <- AuditSupport.currentAuth
        _ <- seen.update(_ :+ Seen(draft, ip, auth.clientId))
      yield ()
    override def record(draft: AuditDraft): IO[AuditUnavailable.type, Unit] = note(draft)
    override def recordBestEffort(draft: AuditDraft): UIO[Unit] = note(draft)
    override def build(draft: AuditDraft): UIO[AuditEvent] = AuditEvents.noop.build(draft)
    override def recordNow(draft: AuditDraft): IO[AuditUnavailable.type, Unit] = note(draft)
    override def committed(event: AuditEvent): UIO[Unit] = ZIO.unit
    val all: UIO[List[Seen]] = seen.get

  private def probe = Ref.make(List.empty[Seen]).map(Probe(_))

  private def failuresOf(events: AuditEvents, maxKeys: Int = 100): ZIO[Scope, Nothing, ClientAuthFailures] =
    ClientAuthFailures.liveWith(1.minute, maxKeys).build.provideSomeEnvironment[Scope](_ ++ ZEnvironment(events)).map(_.get)

  private def failedFrom(failures: ClientAuthFailures, ip: String, clientId: String, reason: String): UIO[Unit] =
    // each call is a request of its own: the context it sets is not seen by the next
    zio.logging.logContext.locallyWith(identity)(Observability.setIp(ip) *> failures.failed(clientId, reason, "client_secret"))

  def spec = suite("ClientAuthFailures")(
    test("failures of one client from one address for one reason are one event with the count, written when the minute is up") {
      for
        events <- probe
        failures <- failuresOf(events)
        _ <- ZIO.foreachDiscard(1 to 5)(_ => failedFrom(failures, "203.0.113.7", "app", "invalid_credentials"))
        before <- events.all
        _ <- TestClock.adjust(1.minute)
        after <- events.all
      yield assertTrue(
        before.isEmpty,
        after.size == 1,
        after.head.draft.kind == AuthAuditKinds.ClientAuthFailed,
        after.head.draft.outcome == AuditOutcome.denied,
        after.head.draft.reason == Some("invalid_credentials"),
        after.head.draft.actor == Some((AuthActors.client, Some("app"))),
        after.head.draft.details == AuthAuditDetails.ClientAuthFailed(method = "client_secret", count = 5),
        // the address and client are the failures', put in the context the event is filled in from
        after.head.ip == Some("203.0.113.7"),
        after.head.clientId == Some("app"),
      )
    },
    test("another address, client or reason is another event") {
      for
        events <- probe
        failures <- failuresOf(events)
        _ <- failedFrom(failures, "203.0.113.7", "app", "invalid_credentials")
        _ <- failedFrom(failures, "203.0.113.8", "app", "invalid_credentials")
        _ <- failedFrom(failures, "203.0.113.7", "other", "invalid_credentials")
        _ <- failedFrom(failures, "203.0.113.7", "app", "method_mismatch")
        _ <- TestClock.adjust(1.minute)
        seen <- events.all
      yield assertTrue(seen.size == 4, seen.map(_.draft.details).forall(_ == AuthAuditDetails.ClientAuthFailed("client_secret", 1)))
    },
    test("nothing is written for a minute with no failure, and the counts start over after each flush") {
      for
        events <- probe
        failures <- failuresOf(events)
        _ <- TestClock.adjust(3.minutes)
        quiet <- events.all
        _ <- failedFrom(failures, "203.0.113.7", "app", "invalid_credentials")
        _ <- TestClock.adjust(1.minute)
        _ <- failedFrom(failures, "203.0.113.7", "app", "invalid_credentials")
        _ <- failedFrom(failures, "203.0.113.7", "app", "invalid_credentials")
        _ <- TestClock.adjust(1.minute)
        seen <- events.all
      yield assertTrue(
        quiet.isEmpty,
        seen.map(_.draft.details) == List(
          AuthAuditDetails.ClientAuthFailed("client_secret", 1),
          AuthAuditDetails.ClientAuthFailed("client_secret", 2),
        ),
      )
    },
    test("memory is bounded: past the limit the failures of new keys are counted together, one event more") {
      for
        events <- probe
        failures <- failuresOf(events, maxKeys = 2)
        _ <- failedFrom(failures, "203.0.113.1", "a", "invalid_credentials")
        _ <- failedFrom(failures, "203.0.113.2", "b", "invalid_credentials")
        _ <- ZIO.foreachDiscard(1 to 50)(i => failedFrom(failures, s"198.51.100.$i", s"made-up-$i", "invalid_credentials"))
        // a key already counted still counts under its own name
        _ <- failedFrom(failures, "203.0.113.1", "a", "invalid_credentials")
        _ <- TestClock.adjust(1.minute)
        seen <- events.all
        byReason = seen.map(s => (s.draft.reason, s.draft.details)).toMap
      yield assertTrue(
        seen.size == 3,
        seen.count(_.draft.reason == Some("overflow")) == 1,
        seen.collectFirst { case s if s.draft.reason == Some("overflow") => s.draft.details }.contains(AuthAuditDetails.ClientAuthFailed("", 50)),
        seen.flatMap(_.clientId).toSet == Set("a", "b"),
      )
    },
    test("what was counted when the service stops is written on the way out") {
      for
        events <- probe
        _ <- ZIO.scoped:
          for
            failures <- failuresOf(events)
            _ <- failedFrom(failures, "203.0.113.7", "app", "invalid_credentials")
          yield ()
        seen <- events.all
      yield assertTrue(seen.map(_.draft.details) == List(AuthAuditDetails.ClientAuthFailed("client_secret", 1)))
    },
    test("a failure with no address in the request is counted under no address, not dropped") {
      for
        events <- probe
        failures <- failuresOf(events)
        _ <- failures.failed("app", "invalid_credentials", "client_secret")
        _ <- TestClock.adjust(1.minute)
        seen <- events.all
      yield assertTrue(
        seen.map(_.ip) == List(None),
        seen.map(_.draft.details).collect { case d: AuthAuditDetails.ClientAuthFailed => d.count } == List(1),
      )
    },
  )
