package versola.edge.partner

import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

/** What a member of a partner's team may do in the console (#549). Ordered: a level also has what the ones after it have. */
enum StaffLevel(val rank: Int) derives JsonCodec:
  /** Everything: the team, the settings, revocations. */
  case admin extends StaffLevel(3)

  /** Clients, resources and edges. */
  case developer extends StaffLevel(2)

  /** Reads those, changes nothing. */
  case viewer extends StaffLevel(1)

  def atLeast(required: StaffLevel): Boolean = rank >= required.rank

/** How an invite names a person. */
enum IdentifierKind derives JsonCodec:
  case phone, email

/** A person who has signed in to the console and is on the team. */
case class StaffMember(subject: String, level: StaffLevel, display: String, addedBy: String, addedAt: Instant)

/** An invitation nobody has taken yet. The identifier is not here: its HMAC and its masked form are. */
case class StaffInvite(
    id: UUID,
    kind: IdentifierKind,
    identifierHash: String,
    masked: String,
    level: StaffLevel,
    invitedBy: String,
    createdAt: Instant,
    expiresAt: Instant,
)

/** The partner's team, in the edge's own database (`partner_staff`, `partner_staff_invites`). */
trait StaffRepository:
  def findMember(subject: String): zio.Task[Option[StaffMember]]

  def members: zio.Task[List[StaffMember]]

  def invites: zio.Task[List[StaffInvite]]

  def findInvite(kind: IdentifierKind, identifierHash: String): zio.Task[Option[StaffInvite]]

  /** Makes the invite, or renews the one for the same identifier: its level, inviter and expiry are the new ones, its id stays. */
  def invite(invite: StaffInvite): zio.Task[Unit]

  /** Turns the invite `invite` into the member `member` in one transaction, so two sign-ins of one person at once admit one
    * member and the invite is spent once. `false` when the invite was already gone (taken by the other, or removed). A member who
    * is already there is left as they are. */
  def admit(invite: UUID, member: StaffMember): zio.Task[Boolean]

  def removeInvite(id: UUID): zio.Task[Boolean]

  def removeMember(subject: String): zio.Task[Boolean]

  def setMemberLevel(subject: String, level: StaffLevel): zio.Task[Boolean]

  def setInviteLevel(id: UUID, level: StaffLevel): zio.Task[Boolean]
