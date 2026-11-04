package versola.audit

import versola.util.audit.{AuditDraft, AuditEvents, AuditKind, AuditOutcome}
import versola.util.http.Observability
import zio.*
import zio.logging.logContext

/** The events that anyone can cause without credentials, coalesced: `client.auth_failed`, and the refusals `token.refused`, a failed
  * `authorize.request` and a refused `par.request`. A client that cannot authenticate, or that asks for what it may not have, does so
  * again and again (a wrong secret in a retry loop, a guess at a secret, a grant that was spent), and one row for each would be the
  * cheapest way to fill the log: so an occurrence is counted in memory under (kind, client, address, reason, details) and the counts are
  * written once a minute, one row each, with `count`.
  *
  * The counts are per instance; the stop of the service flushes them once, within a bound.
  * Memory is bounded: past `maxKeys` distinct keys the occurrences of new ones are counted together under one key, so a flood of made-up
  * client ids is one row more. */
trait ClientAuthFailures:
  /** One failed authentication of `clientId`, from the address of the request being handled, for a `reason` (a closed word:
    * `invalid_credentials`, `invalid_assertion`, `certificate_mismatch`, `method_mismatch`, `secret_required`, `profile_refused`) with
    * the `method` the client registered. */
  def failed(clientId: String, reason: String, method: String): UIO[Unit]

  /** One refusal of the kind `kind`, from the address of the request being handled, for a `reason` (a closed word, an OAuth error code).
    * `details` carry only closed words: they are part of the key. */
  def refused(
      kind: AuditKind,
      outcome: AuditOutcome,
      clientId: Option[String],
      reason: String,
      details: AuthAuditDetails.Coalesced,
  ): UIO[Unit]

object ClientAuthFailures:

  /** Nothing is kept or written. For the specs of what calls it. */
  object noop extends ClientAuthFailures:
    override def failed(clientId: String, reason: String, method: String): UIO[Unit] = ZIO.unit

    override def refused(
        kind: AuditKind,
        outcome: AuditOutcome,
        clientId: Option[String],
        reason: String,
        details: AuthAuditDetails.Coalesced,
    ): UIO[Unit] = ZIO.unit

  private case class Key(
      kind: AuditKind,
      outcome: AuditOutcome,
      clientId: String,
      ip: Option[String],
      reason: String,
      details: AuthAuditDetails.Coalesced,
  )

  /** What a key too many is counted under. */
  private val Overflow =
    Key(AuthAuditKinds.ClientAuthFailed, AuditOutcome.denied, clientId = "", ip = None, reason = "overflow", AuthAuditDetails.ClientAuthFailed("", 0))

  val MaxKeys = 10000
  val FlushInterval: Duration = 1.minute

  val live: ZLayer[AuditEvents & Scope, Nothing, ClientAuthFailures] =
    liveWith(FlushInterval, MaxKeys)

  def liveWith(interval: Duration, maxKeys: Int): ZLayer[AuditEvents & Scope, Nothing, ClientAuthFailures] =
    ZLayer.fromZIO:
      for
        audit <- ZIO.service[AuditEvents]
        counts <- Ref.make(Map.empty[Key, Int])
        impl = Impl(counts, maxKeys)
        flush = flushOnce(counts, audit)
        _ <- (ZIO.sleep(interval) *> flush).forever.forkScoped
        // What the last minute counted is written by a stop that is orderly.
        _ <- ZIO.addFinalizer(flush)
      yield impl

  private class Impl(counts: Ref[Map[Key, Int]], maxKeys: Int) extends ClientAuthFailures:
    override def failed(clientId: String, reason: String, method: String): UIO[Unit] =
      refused(AuthAuditKinds.ClientAuthFailed, AuditOutcome.denied, Some(clientId), reason, AuthAuditDetails.ClientAuthFailed(method, 0))

    override def refused(
        kind: AuditKind,
        outcome: AuditOutcome,
        clientId: Option[String],
        reason: String,
        details: AuthAuditDetails.Coalesced,
    ): UIO[Unit] =
      Observability.currentIp.flatMap: ip =>
        val key = Key(kind, outcome, clientId.getOrElse(""), ip, reason, details)
        counts.update: current =>
          if current.contains(key) || current.size < maxKeys then current.updated(key, current.getOrElse(key, 0) + 1)
          else current.updated(Overflow, current.getOrElse(Overflow, 0) + 1)

  /** Queues what has been counted, one event a key, with the address and client of the occurrences in the context the event is filled
    * from (this fiber is no request's). Best effort: a failure to queue is the writer's to report, and the counts are not put back. */
  private def flushOnce(counts: Ref[Map[Key, Int]], audit: AuditEvents): UIO[Unit] =
    counts.getAndSet(Map.empty).flatMap: taken =>
      ZIO.foreachDiscard(taken): (key, count) =>
        logContext.locallyWith(identity)(
          ZIO.foreachDiscard(key.ip)(Observability.setIp) *>
            Observability.setClientId(key.clientId).when(key.clientId.nonEmpty) *>
            audit.recordBestEffort(
              AuditDraft(
                kind = key.kind,
                outcome = key.outcome,
                reason = Some(key.reason),
                actor = Some((AuthActors.client, Option.when(key.clientId.nonEmpty)(key.clientId))),
                details = key.details.counted(count),
              ),
            ),
        )
