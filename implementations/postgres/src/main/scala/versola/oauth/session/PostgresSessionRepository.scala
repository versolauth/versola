package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.session.model.{SessionId, SessionRecord}
import zio.{Task, Duration}

class PostgresSessionRepository(xa: TransactorZIO) extends SessionRepository:
  override def create(
      id: SessionId,
      record: SessionRecord,
      ttl: Duration,
  ): Task[Unit] = ???
