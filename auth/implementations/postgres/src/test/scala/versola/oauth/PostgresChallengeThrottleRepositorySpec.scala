package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.conversation.limit.{ChallengeThrottleRepositorySpec, PostgresChallengeThrottleRepository}
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresChallengeThrottleRepositorySpec extends PostgresSpec, ChallengeThrottleRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield ChallengeThrottleRepositorySpec.Env(PostgresChallengeThrottleRepository(xa))

  override def beforeEach(env: ChallengeThrottleRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE challenge_throttle".update.run())
    yield ()
