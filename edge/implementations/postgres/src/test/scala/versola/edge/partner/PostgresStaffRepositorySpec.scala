package versola.edge.partner

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresStaffRepositorySpec extends PostgresSpec, StaffRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield StaffRepositorySpec.Env(PostgresStaffRepository(xa))

  override def beforeEach(env: StaffRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE partner_staff, partner_staff_invites".update.run())
    }.unit
