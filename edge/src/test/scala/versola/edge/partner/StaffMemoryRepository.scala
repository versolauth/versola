package versola.edge.partner

import zio.*

import java.util.UUID

/** A [[StaffRepository]] in memory that behaves as the contract of `StaffRepositorySpec` says, for the specs of what is built on it. */
object StaffMemoryRepository:
  def make: UIO[StaffRepository] =
    for
      memberRows <- Ref.make(Map.empty[String, StaffMember])
      inviteRows <- Ref.make(Map.empty[UUID, StaffInvite])
    yield Memory(memberRows, inviteRows)

  /** A repository in memory that behaves as the contract of `StaffRepositorySpec` says. */
  private class Memory(
      memberRows: Ref[Map[String, StaffMember]],
      inviteRows: Ref[Map[UUID, StaffInvite]],
  ) extends StaffRepository:
    override def findMember(subject: String) = memberRows.get.map(_.get(subject))
    override def members: Task[List[StaffMember]] = memberRows.get.map(_.values.toList.sortBy(_.subject))
    override def invites: Task[List[StaffInvite]] = inviteRows.get.map(_.values.toList)
    override def findInvite(kind: IdentifierKind, identifierHash: String) =
      inviteRows.get.map(_.values.find(invite => invite.kind == kind && invite.identifierHash == identifierHash))
    override def invite(invite: StaffInvite) =
      inviteRows.update: all =>
        val id = all.values.find(old => old.kind == invite.kind && old.identifierHash == invite.identifierHash).map(_.id).getOrElse(invite.id)
        all.updated(id, invite.copy(id = id))
    override def admit(invite: UUID, member: StaffMember) =
      inviteRows.modify: all =>
        if all.contains(invite) then true -> all.removed(invite) else false -> all
      .tap(admitted => ZIO.when(admitted)(memberRows.update(m => if m.contains(member.subject) then m else m.updated(member.subject, member))))
    override def removeInvite(id: UUID) = inviteRows.modify(all => all.contains(id) -> all.removed(id))
    override def removeMember(subject: String) = memberRows.modify(all => all.contains(subject) -> all.removed(subject))
    override def setMemberLevel(subject: String, level: StaffLevel) =
      memberRows.modify(all => all.contains(subject) -> all.updatedWith(subject)(_.map(_.copy(level = level))))
    override def setInviteLevel(id: UUID, level: StaffLevel) =
      inviteRows.modify(all => all.contains(id) -> all.updatedWith(id)(_.map(_.copy(level = level))))
