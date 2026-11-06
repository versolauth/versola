package versola.audit

import versola.util.audit.{AuditDraft, AuditEvents, AuditKind, AuditOutcome}
import zio.*

/** The [[ClientAuthFailures]] of the specs of the call sites that only want to see what was counted: each occurrence is queued at once as
  * a row with `count` 1, instead of being counted for a minute. */
final class ImmediateClientAuthFailures(audit: AuditEvents) extends ClientAuthFailures:
  override def failed(clientId: String, reason: String, method: String): UIO[Unit] =
    refused(
      AuthAuditKinds.ClientAuthFailed,
      AuditOutcome.denied,
      Some(clientId),
      reason,
      AuthAuditDetails.ClientAuthFailed(method, 0, assertionJti = None),
    )

  override def refused(
      kind: AuditKind,
      outcome: AuditOutcome,
      clientId: Option[String],
      reason: String,
      details: AuthAuditDetails.Coalesced,
  ): UIO[Unit] =
    audit.recordBestEffort(
      AuditDraft(
        kind = kind,
        outcome = outcome,
        reason = Some(reason),
        actor = Some((AuthActors.client, clientId)),
        userId = None,
        target = None,
        details = details.counted(1),
      ),
    )
