package versola.edge.partner

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Task, URLayer, ZLayer}

import java.util.UUID

class PostgresStaffRepository(xa: TransactorZIO) extends StaffRepository, BasicCodecs:
  given DbCodec[StaffLevel] = DbCodec.StringCodec.biMap(name => StaffLevel.valueOf(name), _.toString)
  given DbCodec[IdentifierKind] = DbCodec.StringCodec.biMap(name => IdentifierKind.valueOf(name), _.toString)
  given DbCodec[StaffMember] = DbCodec.derived[StaffMember]
  given DbCodec[StaffInvite] = DbCodec.derived[StaffInvite]

  override def findMember(subject: String): Task[Option[StaffMember]] =
    xa.connectMeasured("find-partner-staff"):
      sql"SELECT subject, level, display, added_by, added_at FROM partner_staff WHERE subject = $subject".query[StaffMember].run().headOption

  override def members: Task[List[StaffMember]] =
    xa.connectMeasured("list-partner-staff"):
      sql"SELECT subject, level, display, added_by, added_at FROM partner_staff ORDER BY added_at, subject".query[StaffMember].run().toList

  override def invites: Task[List[StaffInvite]] =
    xa.connectMeasured("list-partner-staff-invites"):
      sql"""
        SELECT id, identifier_kind, identifier_hash, identifier_masked, level, invited_by, created_at, expires_at
        FROM partner_staff_invites ORDER BY created_at, id
      """.query[StaffInvite].run().toList

  override def findInvite(kind: IdentifierKind, identifierHash: String): Task[Option[StaffInvite]] =
    xa.connectMeasured("find-partner-staff-invite"):
      sql"""
        SELECT id, identifier_kind, identifier_hash, identifier_masked, level, invited_by, created_at, expires_at
        FROM partner_staff_invites WHERE identifier_kind = $kind AND identifier_hash = $identifierHash
      """.query[StaffInvite].run().headOption

  override def invite(invite: StaffInvite): Task[Unit] =
    xa.connectMeasured("invite-partner-staff"):
      sql"""
        INSERT INTO partner_staff_invites
          (id, identifier_kind, identifier_hash, identifier_masked, level, invited_by, created_at, expires_at)
        VALUES (${invite.id}, ${invite.kind}, ${invite.identifierHash}, ${invite.masked}, ${invite.level}, ${invite.invitedBy},
                ${invite.createdAt}, ${invite.expiresAt})
        ON CONFLICT (identifier_kind, identifier_hash) DO UPDATE SET
          identifier_masked = EXCLUDED.identifier_masked,
          level = EXCLUDED.level,
          invited_by = EXCLUDED.invited_by,
          created_at = EXCLUDED.created_at,
          expires_at = EXCLUDED.expires_at
      """.update.run()
    .unit

  override def admit(invite: UUID, member: StaffMember): Task[Boolean] =
    xa.transactMeasured("admit-partner-staff"):
      val spent = sql"DELETE FROM partner_staff_invites WHERE id = $invite".update.run()
      if spent == 0 then false
      else
        sql"""
          INSERT INTO partner_staff (subject, level, display, added_by, added_at)
          VALUES (${member.subject}, ${member.level}, ${member.display}, ${member.addedBy}, ${member.addedAt})
          ON CONFLICT (subject) DO NOTHING
        """.update.run()
        true

  override def removeInvite(id: UUID): Task[Boolean] =
    xa.connectMeasured("remove-partner-staff-invite")(sql"DELETE FROM partner_staff_invites WHERE id = $id".update.run()).map(_ > 0)

  override def removeMember(subject: String): Task[Boolean] =
    xa.connectMeasured("remove-partner-staff")(sql"DELETE FROM partner_staff WHERE subject = $subject".update.run()).map(_ > 0)

  override def setMemberLevel(subject: String, level: StaffLevel): Task[Boolean] =
    xa.connectMeasured("set-partner-staff-level")(sql"UPDATE partner_staff SET level = $level WHERE subject = $subject".update.run()).map(_ > 0)

  override def setInviteLevel(id: UUID, level: StaffLevel): Task[Boolean] =
    xa.connectMeasured("set-partner-staff-invite-level")(sql"UPDATE partner_staff_invites SET level = $level WHERE id = $id".update.run()).map(_ > 0)

object PostgresStaffRepository:
  val live: URLayer[TransactorZIO, StaffRepository] = ZLayer.fromFunction(PostgresStaffRepository(_))
