package versola.user

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec.given
import versola.user.model.*
import versola.util.{Email, Phone}
import zio.{Clock, Task, ZIO}
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, LocalDate}
import java.util.UUID

class PostgresUserRepository(
    xa: TransactorZIO,
) extends UserRepository:

  override def findOrCreate(userId: UserId, credential: Either[Email, Phone]): Task[(UserRecord, WasCreated)] =
    for
      now <- Clock.instant
      (user, wasCreated) <- xa.connect:
        credential match
          case Left(email) => createByEmailQuery(userId, email, now).run().head
          case Right(phone) => createByPhoneQuery(userId, phone, now).run().head
    yield (user, WasCreated(wasCreated))

  override def create(id: UserId): Task[UserRecord] =
    for
      now <- Clock.instant
      user <- xa.connect:
        createQuery(id, now).run().head
    yield user

  private def createByEmailQuery(id: UserId, email: Email, now: Instant) =
    sql"""insert into users (id, email, phone, login, claims)
          values ($id, $email, null, null, '{}'::jsonb)
          on conflict (email) where email is not null
          do update set email = excluded.email
          returning id, email, phone, login, claims, (xmax = 0) as created
       """.returning[(UserRecord, Boolean)]

  private def createByPhoneQuery(id: UserId, phone: Phone, now: Instant) =
    sql"""insert into users (id, email, phone, login, claims)
          values ($id, null, $phone, null, '{}'::jsonb)
          on conflict (phone) where phone is not null
            do update set phone = excluded.phone
          returning id, email, phone, login, claims, (xmax = 0) as created
       """.returning[(UserRecord, Boolean)]

  private def createQuery(id: UserId, now: Instant) =
    sql"""insert into users (id, email, phone, login, claims)
          values ($id, null, null, null, '{}'::jsonb)
          returning id, email, phone, login, claims
       """.returning[UserRecord]

  override def find(id: UserId): Task[Option[UserRecord]] =
    xa.connect:
      sql"select id, email, phone, login, claims from users where id = $id"
        .query[UserRecord]
        .run()
        .headOption

  override def findByCredential(credential: Either[Email, Phone]): Task[Option[UserRecord]] =
    xa.connect:
      credential match
        case Left(email) => findByEmailQuery(email).run().headOption
        case Right(phone) => findByPhoneQuery(phone).run().headOption

  private def findByPhoneQuery(phone: Phone) =
    sql"select id, email, phone, login, claims from users where phone = $phone".query[UserRecord]

  private def findByEmailQuery(email: Email) =
    sql"select id, email, phone, login, claims from users where email = $email".query[UserRecord]


  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Email] = DbCodec.StringCodec.biMap(Email(_), identity[String])
  given DbCodec[Phone] = DbCodec.StringCodec.biMap(Phone(_), identity[String])
  given DbCodec[Login] = DbCodec.StringCodec.biMap(Login(_), identity[String])
  given DbCodec[FirstName] = DbCodec.StringCodec.biMap(FirstName(_), identity[String])
  given DbCodec[MiddleName] = DbCodec.StringCodec.biMap(MiddleName(_), identity[String])
  given DbCodec[LastName] = DbCodec.StringCodec.biMap(LastName(_), identity[String])
  given DbCodec[BirthDate] = DbCodec.LocalDateCodec.biMap(BirthDate(_), identity[LocalDate])

  given DbCodec[Json.Obj] = DbCodec.StringCodec.biMap(
    _.fromJson[Json.Obj].getOrElse(Json.Obj()),
    _.toJson
  )

  given DbCodec[UserRecord] = DbCodec.derived[UserRecord]