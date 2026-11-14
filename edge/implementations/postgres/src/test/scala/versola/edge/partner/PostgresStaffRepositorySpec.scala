package versola.edge.partner

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.test.*
import zio.{ZIO, ZLayer}

import java.time.Instant
import java.util.UUID

object PostgresStaffRepositorySpec extends PostgresSpec, StaffRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield StaffRepositorySpec.Env(PostgresStaffRepository(xa))

  override def beforeEach(env: StaffRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE partner_staff, partner_staff_invites, partner_staff_config_admins, partner_staff_events".update.run())
    }.unit

  private val at = Instant.parse("2026-11-12T10:00:00Z")

  /** The value sets are the Scala enums', not the schema's: what a row holds that is not one of them is read as a failure, never as a default. */
  override def implementationCases(env: StaffRepositorySpec.Env) =
    List(
      test("every level and every kind of identifier is stored and read back as it was") {
        for
          _ <- ZIO.foreachDiscard(StaffLevel.values.toList)(level =>
            env.repository.invite(StaffInvite(
              UUID.randomUUID(),
              IdentifierKind.phone,
              s"phone-$level",
              "+7••11",
              level,
              "config",
              at,
              at.plusSeconds(3600),
            )),
          )
          _ <- ZIO.foreachDiscard(IdentifierKind.values.toList)(kind =>
            env.repository.invite(StaffInvite(UUID.randomUUID(), kind, s"kind-$kind", "x", StaffLevel.viewer, "config", at, at.plusSeconds(3600))),
          )
          invites <- env.repository.invites(at)
        yield assertTrue(
          StaffLevel.values.toSet.subsetOf(invites.map(_.level).toSet),
          IdentifierKind.values.toSet.subsetOf(invites.map(_.kind).toSet),
        )
      },
      test("a stored level or kind that is not one of the enum's fails to read, loudly, and does not become a default") {
        ZIO.serviceWithZIO[TransactorZIO] { xa =>
          for
            _ <- xa.connect(
              sql"INSERT INTO partner_staff (subject, level, display, added_by, added_at) VALUES ('sub-x', 'owner', 'X', 'config', $at)".update.run(),
            )
            _ <- xa.connect(
              sql"""INSERT INTO partner_staff_invites (id, identifier_kind, identifier_hash, identifier_masked, level, invited_by, created_at, expires_at)
                    VALUES (${UUID.randomUUID()}, 'fax', 'h', 'm', 'viewer', 'config', $at, ${at.plusSeconds(3600)})""".update.run(),
            )
            member <- env.repository.findMember("sub-x").exit
            invites <- env.repository.invites(at).exit
          yield assertTrue(member.isFailure, invites.isFailure)
        }
      },
    )
