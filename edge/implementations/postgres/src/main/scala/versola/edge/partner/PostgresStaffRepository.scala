package versola.edge.partner

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Task, URLayer, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresStaffRepository(xa: TransactorZIO) extends StaffRepository, BasicCodecs:
  given DbCodec[StaffLevel] = DbCodec.StringCodec.biMap(name => StaffLevel.valueOf(name), _.toString)
  given DbCodec[IdentifierKind] = DbCodec.StringCodec.biMap(name => IdentifierKind.valueOf(name), _.toString)
  given DbCodec[StaffMember] = DbCodec.derived[StaffMember]
  given DbCodec[StaffInvite] = DbCodec.derived[StaffInvite]
  given DbCodec[TeamEvent] = DbCodec.derived[TeamEvent]

  /** The key of the advisory lock the changes that could remove the last administrator are taken under. */
  private val AdminLock = 7_101_564L

  override def findMember(subject: String): Task[Option[StaffMember]] =
    xa.connectMeasured("find-partner-staff"):
      sql"SELECT subject, level, display, added_by, added_at FROM partner_staff WHERE subject = $subject".query[StaffMember].run().headOption

  override def members: Task[List[StaffMember]] =
    xa.connectMeasured("list-partner-staff"):
      sql"SELECT subject, level, display, added_by, added_at FROM partner_staff ORDER BY added_at, subject".query[StaffMember].run().toList

  override def invites(now: Instant): Task[List[StaffInvite]] =
    xa.connectMeasured("list-partner-staff-invites"):
      sql"""
        SELECT id, identifier_kind, identifier_hash, identifier_masked, level, invited_by, created_at, expires_at
        FROM partner_staff_invites WHERE expires_at > $now ORDER BY created_at, id
      """.query[StaffInvite].run().toList

  override def purgeExpired(now: Instant): Task[Int] =
    xa.connectMeasured("purge-partner-staff-invites")(sql"DELETE FROM partner_staff_invites WHERE expires_at <= $now".update.run())

  override def claimConfigAdmin(kind: IdentifierKind, identifierHash: String, now: Instant): Task[Boolean] =
    xa.transactMeasured("claim-partner-config-admin"):
      sql"""
        INSERT INTO partner_staff_config_admins (identifier_kind, identifier_hash, applied_at, admitted_at)
        VALUES ($kind, $identifierHash, $now, NULL) ON CONFLICT DO NOTHING
      """.update.run()
      sql"SELECT admitted_at IS NULL FROM partner_staff_config_admins WHERE identifier_kind = $kind AND identifier_hash = $identifierHash"
        .query[Boolean].run().headOption.getOrElse(false)

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

  override def admit(invite: UUID, member: StaffMember): Task[Option[StaffMember]] =
    xa.transactMeasured("admit-partner-staff"):
      // taken and deleted in one statement: of two sign-ins at once, one gets the row; its level is what the row says at that moment
      val taken = sql"""
        DELETE FROM partner_staff_invites WHERE id = $invite
        RETURNING identifier_kind, identifier_hash, level, invited_by
      """.query[(IdentifierKind, String, StaffLevel, String)].run().headOption
      taken.map: (kind, identifierHash, level, invitedBy) =>
        val admitted = member.copy(level = level, addedBy = invitedBy)
        sql"""
          INSERT INTO partner_staff (subject, level, display, added_by, added_at)
          VALUES (${admitted.subject}, ${admitted.level}, ${admitted.display}, ${admitted.addedBy}, ${admitted.addedAt})
          ON CONFLICT (subject) DO NOTHING
        """.update.run()
        sql"""
          UPDATE partner_staff_config_admins SET admitted_at = ${admitted.addedAt}
          WHERE identifier_kind = $kind AND identifier_hash = $identifierHash AND admitted_at IS NULL
        """.update.run()
        admitted

  override def removeInvite(id: UUID): Task[Boolean] =
    xa.connectMeasured("remove-partner-staff-invite")(sql"DELETE FROM partner_staff_invites WHERE id = $id".update.run()).map(_ > 0)

  /** Under the lock, so that two changes at once (two administrators demoting each other, two replicas) see each other's: whoever is second
    * finds the first one's result and is refused if it would leave nobody. */
  private def guarded(name: String, subject: String, demotes: StaffLevel => Boolean)(change: DbTx ?=> Unit): Task[Change] =
    xa.transactMeasured(name):
      sql"SELECT pg_advisory_xact_lock($AdminLock)::text".query[String].run()
      sql"SELECT level FROM partner_staff WHERE subject = $subject".query[StaffLevel].run().headOption match
        case None => Change.Missing
        case Some(level) =>
          val others = sql"SELECT count(*) FROM partner_staff WHERE level = 'admin' AND subject <> $subject".query[Long].run().head
          if level == StaffLevel.admin && demotes(level) && others == 0 then Change.LastAdmin
          else
            change
            Change.Done

  override def removeMember(subject: String): Task[Change] =
    guarded("remove-partner-staff", subject, _ => true)(sql"DELETE FROM partner_staff WHERE subject = $subject".update.run())

  override def setMemberLevel(subject: String, level: StaffLevel): Task[Change] =
    guarded("set-partner-staff-level", subject, _ => level != StaffLevel.admin)(
      sql"UPDATE partner_staff SET level = $level WHERE subject = $subject".update.run(),
    )

  override def setInviteLevel(id: UUID, level: StaffLevel): Task[Boolean] =
    xa.connectMeasured("set-partner-staff-invite-level")(sql"UPDATE partner_staff_invites SET level = $level WHERE id = $id".update.run()).map(_ > 0)

  override def record(event: TeamEvent): Task[Unit] =
    xa.connectMeasured("record-partner-staff-event"):
      sql"""
        INSERT INTO partner_staff_events (id, at, actor, action, target, detail)
        VALUES (${event.id}, ${event.at}, ${event.actor}, ${event.action}, ${event.target}, ${event.detail})
      """.update.run()
    .unit

  override def events(from: Option[Instant], before: Option[Instant], limit: Int): Task[List[TeamEvent]] =
    val lower = from.getOrElse(Instant.EPOCH)
    val upper = before.getOrElse(Instant.parse("9999-01-01T00:00:00Z"))
    xa.connectMeasured("list-partner-staff-events"):
      sql"""
        SELECT id, at, actor, action, target, detail FROM partner_staff_events
        WHERE at >= $lower AND at < $upper ORDER BY at DESC, id LIMIT $limit
      """.query[TeamEvent].run().toList

object PostgresStaffRepository:
  val live: URLayer[TransactorZIO, StaffRepository] = ZLayer.fromFunction(PostgresStaffRepository(_))
