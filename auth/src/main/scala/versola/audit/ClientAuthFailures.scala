package versola.audit

import versola.util.audit.{ActorType, AuditDraft, AuditEvents, AuditKinds, AuditOutcome}
import versola.util.http.Observability
import zio.*
import zio.logging.logContext

/** `client.auth_failed`, coalesced. A client that cannot authenticate does so again and again (a wrong secret in a retry loop, a guess
  * at a secret), and one row for each would be the cheapest way to fill the log: so a failure is counted in memory under (client,
  * address, reason) and the counts are written once a minute, one row each, with `count`.
  *
  * The counts are per instance and an instance that stops loses at most the last minute (the stop flushes once, within a bound).
  * Memory is bounded: past `maxKeys` distinct keys the failures of new ones are counted together under one key, so a flood of made-up
  * client ids is one row more. */
trait ClientAuthFailures:
  /** One failed authentication of `clientId`, from the address of the request being handled, for a `reason` (a closed word:
    * `invalid_credentials`, `invalid_assertion`, `certificate_mismatch`, `method_mismatch`, `secret_required`, `profile_refused`) with
    * the `method` the client registered. */
  def failed(clientId: String, reason: String, method: String): UIO[Unit]

object ClientAuthFailures:

  /** Nothing is kept or written. For the specs of what calls it. */
  object noop extends ClientAuthFailures:
    override def failed(clientId: String, reason: String, method: String): UIO[Unit] = ZIO.unit

  private case class Key(clientId: String, ip: Option[String], reason: String, method: String)

  /** What a key too many is counted under. */
  private val Overflow = Key(clientId = "", ip = None, reason = "overflow", method = "")

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
        // What the last minute counted is not lost to a stop that is orderly.
        _ <- ZIO.addFinalizer(flush)
      yield impl

  private class Impl(counts: Ref[Map[Key, Int]], maxKeys: Int) extends ClientAuthFailures:
    override def failed(clientId: String, reason: String, method: String): UIO[Unit] =
      Observability.currentIp.flatMap: ip =>
        val key = Key(clientId, ip, reason, method)
        counts.update: current =>
          if current.contains(key) || current.size < maxKeys then current.updated(key, current.getOrElse(key, 0) + 1)
          else current.updated(Overflow, current.getOrElse(Overflow, 0) + 1)

  /** Writes what has been counted, one event a key, with the address and client of the failures in the context the event is filled from
    * (this fiber is no request's). Best effort: a failure to write is the writer's to report, and the counts are not put back. */
  private def flushOnce(counts: Ref[Map[Key, Int]], audit: AuditEvents): UIO[Unit] =
    counts.getAndSet(Map.empty).flatMap: taken =>
      ZIO.foreachDiscard(taken): (key, count) =>
        logContext.locallyWith(identity)(
          ZIO.foreachDiscard(key.ip)(Observability.setIp) *>
            Observability.setClientId(key.clientId).when(key.clientId.nonEmpty) *>
            audit.recordBestEffort(
              AuditDraft(
                kind = AuditKinds.ClientAuthFailed,
                outcome = AuditOutcome.denied,
                reason = Some(key.reason),
                actor = Some((ActorType.client, Option.when(key.clientId.nonEmpty)(key.clientId))),
                details = AuthAuditDetails.ClientAuthFailed(method = key.method, count = count),
              ),
            ),
        )
