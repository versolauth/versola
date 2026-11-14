package versola.edge.partner

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

import java.time.Instant
import java.util.UUID

object PostgresStaffRepositorySpec extends PostgresSpec, StaffRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield StaffRepositorySpec.Env(
        repository = PostgresStaffRepository(xa),
        storeRaw = (subject, rawLevel, rawKind, at) =>
          xa.connect(
            sql"INSERT INTO partner_staff (subject, level, display, added_by, added_at) VALUES ($subject, $rawLevel, 'X', 'config', $at)".update.run(),
          ) *> xa.connect(
            sql"""INSERT INTO partner_staff_invites (id, identifier_kind, identifier_hash, identifier_masked, level, invited_by, created_at, expires_at)
                  VALUES (${UUID.randomUUID()}, $rawKind, 'h', 'm', 'viewer', 'config', $at, ${at.plusSeconds(3600)})""".update.run(),
          ).unit,
      )

  override def beforeEach(env: StaffRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE partner_staff, partner_staff_invites, partner_staff_config_admins, partner_staff_events".update.run())
    }.unit
