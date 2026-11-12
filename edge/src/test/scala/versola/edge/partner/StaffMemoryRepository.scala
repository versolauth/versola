package versola.edge.partner

import zio.*

import java.time.Instant
import java.util.UUID

/** A [[StaffRepository]] in memory that behaves as the contract of `StaffRepositorySpec` says, for the specs of what is built on it. */
object StaffMemoryRepository:
  def make: UIO[StaffRepository] =
    for
      memberRows <- Ref.make(Map.empty[String, StaffMember])
      inviteRows <- Ref.make(Map.empty[UUID, StaffInvite])
      eventRows <- Ref.make(List.empty[TeamEvent])
      configRows <- Ref.make(Map.empty[(IdentifierKind, String), Boolean])
    yield Memory(memberRows, inviteRows, eventRows, configRows)

  /** A repository in memory that behaves as the contract of `StaffRepositorySpec` says. */
  private class Memory(
      memberRows: Ref[Map[String, StaffMember]],
      inviteRows: Ref[Map[UUID, StaffInvite]],
      eventRows: Ref[List[TeamEvent]],
      configRows: Ref[Map[(IdentifierKind, String), Boolean]],
  ) extends StaffRepository:
    override def findMember(subject: String) = memberRows.get.map(_.get(subject))
    override def members: Task[List[StaffMember]] = memberRows.get.map(_.values.toList.sortBy(_.subject))
    override def invites(now: Instant) = inviteRows.get.map(_.values.toList.filter(_.expiresAt.isAfter(now)))
    override def purgeExpired(now: Instant) =
      inviteRows.modify(all => all.count(_._2.expiresAt.isAfter(now) == false) -> all.filter(_._2.expiresAt.isAfter(now)))
    override def claimConfigAdmin(kind: IdentifierKind, identifierHash: String, now: Instant) =
      configRows.modify(all =>
        !all.getOrElse((kind, identifierHash), false) -> (if all.contains((kind, identifierHash)) then all
                                                          else all.updated((kind, identifierHash), false)),
      )
    override def findInvite(kind: IdentifierKind, identifierHash: String) =
      inviteRows.get.map(_.values.find(invite => invite.kind == kind && invite.identifierHash == identifierHash))
    override def invite(invite: StaffInvite) =
      inviteRows.update: all =>
        val id = all.values.find(old => old.kind == invite.kind && old.identifierHash == invite.identifierHash).map(_.id).getOrElse(invite.id)
        all.updated(id, invite.copy(id = id))
    override def admit(invite: UUID, member: StaffMember) =
      inviteRows.modify: all =>
        all.get(invite) match
          case Some(taken) => Some(taken) -> all.removed(invite)
          case None => None -> all
      .tap(taken =>
        ZIO.foreachDiscard(taken)(taken =>
          memberRows.update(m => if m.contains(member.subject) then m else m.updated(member.subject, member)) *>
            configRows.update(all =>
              if all.contains((taken.kind, taken.identifierHash)) then all.updated((taken.kind, taken.identifierHash), true) else all,
            ),
        ),
      ).map(_.isDefined)
    override def removeInvite(id: UUID) = inviteRows.modify(all => all.contains(id) -> all.removed(id))
    private def guarded(subject: String, demotes: Boolean)(change: Map[String, StaffMember] => Map[String, StaffMember]): UIO[Change] =
      memberRows.modify: all =>
        all.get(subject) match
          case None => Change.Missing -> all
          case Some(member) =>
            val others = all.values.count(m => m.level == StaffLevel.admin && m.subject != subject)
            if member.level == StaffLevel.admin && demotes && others == 0 then Change.LastAdmin -> all
            else Change.Done -> change(all)
    override def removeMember(subject: String) = guarded(subject, demotes = true)(_.removed(subject))
    override def setMemberLevel(subject: String, level: StaffLevel) =
      guarded(subject, demotes = level != StaffLevel.admin)(_.updatedWith(subject)(_.map(_.copy(level = level))))
    override def setInviteLevel(id: UUID, level: StaffLevel) =
      inviteRows.modify(all => all.contains(id) -> all.updatedWith(id)(_.map(_.copy(level = level))))
    override def record(event: TeamEvent) = eventRows.update(event :: _)
    override def events(from: Option[Instant], before: Option[Instant], limit: Int) =
      eventRows.get.map(_.filter(e => from.forall(!e.at.isBefore(_)) && before.forall(e.at.isBefore(_))).sortBy(_.at)(using
        Ordering[Instant].reverse,
      ).take(limit))
