package versola.configuration.delivery

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.central.configuration.delivery.DeliverySettingsRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresDeliverySettingsRepositorySpec extends PostgresSpec, DeliverySettingsRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield DeliverySettingsRepositorySpec.Env(PostgresDeliverySettingsRepository(xa))

  override def beforeEach(env: DeliverySettingsRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE delivery_settings".update.run())
    }.unit
