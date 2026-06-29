package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.login.{LoginRecord, LoginRepository}
import versola.edge.model.{CodeVerifier, PresetId, State}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task, ZLayer}

class PostgresLoginRepository(xa: TransactorZIO) extends LoginRepository, BasicCodecs:
  given DbCodec[CodeVerifier] = DbCodec.StringCodec.biMap(CodeVerifier(_), identity[String])
  given DbCodec[State] = DbCodec.StringCodec.biMap(State(_), identity[String])
  given DbCodec[PresetId] = DbCodec.StringCodec.biMap(PresetId(_), identity[String])
  given DbCodec[LoginRecord] = DbCodec.derived[LoginRecord]

  override def create(
      loginId: String,
      data: LoginRecord,
      ttl: Duration,
  ): Task[Unit] =
    Clock.instant.flatMap { now =>
      val expiresAt = now.plusSeconds(ttl.toSeconds)
      xa.connectMeasured("create-login") {
        sql"""
          INSERT INTO pending_logins (login_id, state, code_verifier, preset_id, expires_at)
          VALUES ($loginId, ${data.state}, ${data.codeVerifier}, ${data.presetId}, $expiresAt)
          ON CONFLICT (login_id) DO UPDATE SET
            state = EXCLUDED.state,
            code_verifier = EXCLUDED.code_verifier,
            preset_id = EXCLUDED.preset_id,
            expires_at = EXCLUDED.expires_at
        """.update.run()
      }.unit
    }

  override def find(loginId: String): Task[Option[LoginRecord]] =
    Clock.instant.flatMap { now =>
      xa.connectMeasured("find-login") {
        sql"""
          SELECT code_verifier, preset_id, state
          FROM pending_logins
          WHERE login_id = $loginId AND expires_at > $now
        """
          .query[LoginRecord]
          .run()
          .headOption
      }
    }

  override def findByState(state: State): Task[Option[LoginRecord]] =
    Clock.instant.flatMap { now =>
      xa.connectMeasured("find-login-by-state") {
        sql"""
          SELECT code_verifier, preset_id, state
          FROM pending_logins
          WHERE state = $state AND expires_at > $now
        """
          .query[LoginRecord]
          .run()
          .headOption
      }
    }

  override def delete(loginId: String): Task[Unit] =
    xa.connectMeasured("delete-login") {
      sql"""
        DELETE FROM pending_logins WHERE login_id = $loginId
      """.update.run()
    }.unit

  override def deleteByState(state: State): Task[Unit] =
    xa.connectMeasured("delete-login-by-state") {
      sql"""
        DELETE FROM pending_logins WHERE state = $state
      """.update.run()
    }.unit

