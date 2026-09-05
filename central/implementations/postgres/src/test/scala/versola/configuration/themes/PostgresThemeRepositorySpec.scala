package versola.configuration.themes

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.themes.ThemeRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresThemeRepositorySpec extends PostgresSpec, ThemeRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield ThemeRepositorySpec.Env(PostgresThemeRepository(xa))

  // Themes has no ON DELETE CASCADE of its own into other tables, but oauth_clients has a
  // NOT NULL FK *into* themes, which makes a plain `TRUNCATE TABLE themes` fail outright
  // (Postgres refuses to truncate a table something else references). Truncating `tenants`
  // CASCADE instead clears every table that hangs off it -- themes included -- the same
  // reset-everything approach `PostgresOAuthClientRepositorySpec` already relies on.
  override def beforeEach(env: ThemeRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE tenants RESTART IDENTITY CASCADE".update.run()) *>
        xa.connect(sql"INSERT INTO tenants (id, description) VALUES ('tenant-a', 'Tenant A')".update.run())
    }.unit
