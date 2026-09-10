package versola.oauth.dpop

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task, ZLayer}

class PostgresDpopProofRepository(xa: TransactorZIO) extends DpopProofRepository, BasicCodecs:

  override def recordIfAbsent(jkt: String, jti: String, ttl: Duration): Task[Boolean] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("record-dpop-proof-if-absent"):
        // A row surviving past its own `expires_at` (cleanup hasn't reaped it yet) must not be
        // treated as a live replay guard -- the UPDATE branch only fires, and only overwrites
        // it, when it's already expired, so a still-valid row's INSERT keeps losing the
        // conflict and reports a replay exactly as before.
        sql"""
          INSERT INTO dpop_proofs (jkt, jti, expires_at)
          VALUES ($jkt, $jti, ${now.plusSeconds(ttl.toSeconds)})
          ON CONFLICT (jkt, jti) DO UPDATE
            SET expires_at = excluded.expires_at
            WHERE dpop_proofs.expires_at <= $now
          RETURNING dpop_proofs.jkt
        """.query[String].run()
          .nonEmpty

object PostgresDpopProofRepository:
  def live: ZLayer[TransactorZIO, Throwable, DpopProofRepository] =
    ZLayer.fromFunction(PostgresDpopProofRepository(_))
