package versola.antifraud

import versola.oauth.client.model.TenantId

import java.time.Instant
import java.util.UUID

/** What a detector found, as a list entry it suggests (a row of `af_proposal`). Nothing acts on it until it is
  * approved; approving it makes the entry through [[AntifraudLists.create]] with [[toNewListEntry]].
  *
  * @param evidence what the detector saw, as a JSON object, for whoever decides
  * @param ttl      how long the entry lives once made
  */
case class AntifraudProposal(
    id: UUID,
    tenantId: TenantId,
    kind: ProposalKind,
    valueType: AntifraudValueType,
    value: String,
    evidence: String,
    suggestedAction: AntifraudListType,
    ttl: zio.Duration,
    status: ProposalStatus,
    createdAt: Instant,
    decidedBy: Option[String],
    decidedAt: Option[Instant],
):

  /** The entry to make when the proposal is approved at `now`. Never a shadow one: whoever approves has seen the
    * evidence, and a shadow entry is for a dry run the operator chooses. */
  def toNewListEntry(now: Instant): NewListEntry =
    NewListEntry(
      listType = suggestedAction,
      valueType = valueType,
      value = value,
      tenantId = Some(tenantId),
      expiresAt = Some(now.plusSeconds(ttl.getSeconds)),
      reason = Some(s"${kind.toString}: $evidence"),
      source = s"detector:${kind.toString}",
      shadow = false,
    )

/** The detector that made a proposal. A label of the metrics and a column, so closed. */
enum ProposalKind:
  /** A range whose requests jumped against its baseline while fewer of its codes were verified. */
  case prefix_anomaly

  /** A range where codes were entered after a fake send and none was verified. */
  case fake_verify

enum ProposalStatus:
  case open, approved, rejected, expired
