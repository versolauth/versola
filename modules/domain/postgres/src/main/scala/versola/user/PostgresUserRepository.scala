package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec.given
import com.fasterxml.uuid.NoArgGenerator
import versola.user.model.*
import zio.{Clock, Task, ZIO}

import java.time.{Instant, LocalDate}
import java.util.UUID

class PostgresUserRepository(
    xa: TransactorZIO,
    userIdGenerator: NoArgGenerator,
) extends UserRepository:

  override def findOrCreateByEmail(email: Email): Task[(UserRecord, WasCreated)] =
    for
      now <- Clock.instant
      (user, wasCreated) <- xa.connect:
        val id = UserId(userIdGenerator.generate())
        createByEmailQuery(id, email, now).run().head
    yield (user, WasCreated(wasCreated))

  override def create(id: UserId): Task[UserRecord] =
    for
      now <- Clock.instant
      user <- xa.connect:
        createQuery(id, now).run().head
    yield user

  private def createByEmailQuery(id: UserId, email: Email, now: Instant) =
    sql"""insert into users (id, email, updated_at)
          values ($id, $email, $now)
          on conflict (email) do update set updated_at = users.updated_at
          returning *, (xmax = 0) as created
       """.returning[(UserRecord, Boolean)]

  private def createQuery(id: UserId, now: Instant) =
    sql"""insert into users (id, updated_at)
          values ($id, $now)
          returning *
       """.returning[UserRecord]

  override def find(id: UserId): Task[Option[UserRecord]] =
    xa.connect:
      sql"select * from users where id = $id"
        .query[UserRecord]
        .run()
        .headOption

  override def findByEmail(email: Email): Task[Option[UserRecord]] =
    xa.connect:
      findByEmailQuery(email).run().headOption

  private def findByEmailQuery(email: Email) =
    sql"select * from users where email = $email".query[UserRecord]

  override def update(
      userId: UserId,
      email: Option[Option[Email]],
      firstName: Option[Option[FirstName]],
      middleName: Option[Option[MiddleName]],
      lastName: Option[Option[LastName]],
      birthDate: Option[Option[BirthDate]],
  ): Task[Unit] =
    for
      now <- Clock.instant
      updateEmailPart = email.map(email => sql"email = ${email.orNull}")
      updateFirstNamePart = firstName.map(firstName => sql"first_name = ${firstName.orNull}")
      updateMiddleNamePart = middleName.map(middleName => sql"middle_name = ${middleName.orNull}")
      updateLastNamePart = lastName.map(lastName => sql"last_name = ${lastName.orNull}")
      updateBirthDatePart = birthDate.map(birthDate => sql"birth_date = ${birthDate.orNull}")
      fragments = List(
        updateEmailPart,
        updateFirstNamePart,
        updateMiddleNamePart,
        updateLastNamePart,
        updateBirthDatePart,
      ).flatten
      _ <- ZIO.unit.when(fragments.isEmpty).someOrElseZIO:
        val fragment = fragments.reduce((a, b) => sql"$a, $b")
        xa.connect:
          val query = sql"update users set $fragment, updated_at = $now where id = $userId".update
          query.run()
    yield ()

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Email] = DbCodec.StringCodec.biMap(Email(_), identity[String])
  given DbCodec[FirstName] = DbCodec.StringCodec.biMap(FirstName(_), identity[String])
  given DbCodec[MiddleName] = DbCodec.StringCodec.biMap(MiddleName(_), identity[String])
  given DbCodec[LastName] = DbCodec.StringCodec.biMap(LastName(_), identity[String])
  given DbCodec[BirthDate] = DbCodec.LocalDateCodec.biMap(BirthDate(_), identity[LocalDate])
  given DbCodec[UserRecord] = DbCodec.derived[UserRecord]
