package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec.given
import versola.user.model.Email
import versola.util.CacheSource
import zio.{Task, ZIO}

class PostgresBanRepository(xa: TransactorZIO) extends BanRepository with CacheSource[Set[Email]]:

  override def isBanned(email: Email): Task[Boolean] =
    xa.connect:
      sql"select id from bans where id=${email: String}".query[String].run()
    .map(_.nonEmpty)

  override def ban(email: Email): Task[Unit] =
    xa.connect:
      sql"insert into bans (id) values (${email: String})"
        .update
        .run()
      ()
    .catchSome:
      case ex if ex.getMessage.contains("bans_pkey") => ZIO.unit

  override def getAll: Task[Set[Email]] =
    xa.connect:
      sql"select id from bans".query[String].run()
    .map(_.toSet.map(id => Email(id)))
