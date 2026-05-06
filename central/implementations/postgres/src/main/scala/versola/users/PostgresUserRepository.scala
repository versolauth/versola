package versola.users

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.users.{Login, OutboxEvent, OutboxRecord, UserConflict, UserId, UserIndexRecord, UserRepository}
import versola.util.Patch.toUpdate
import versola.util.postgres.BasicCodecs
import versola.util.{Email, Patch, Phone, SecureRandom}
import zio.json.ast.Json
import zio.{Clock, Duration, IO, Task, ZLayer}

import java.sql.SQLException
import java.time.Instant
import java.util.UUID

class PostgresUserRepository(xa: TransactorZIO, secureRandom: SecureRandom) extends UserRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Email] = DbCodec.StringCodec.biMap(Email(_), identity[String])
  given DbCodec[Phone] = DbCodec.StringCodec.biMap(Phone(_), identity[String])
  given DbCodec[Login] = DbCodec.StringCodec.biMap(Login(_), identity[String])
  given DbCodec[UserIndexRecord] = DbCodec.derived
  given DbCodec[Json.Obj] = jsonBCodec[Json.Obj]
  given DbCodec[OutboxEvent] = dbCodecFromJsonCodec[OutboxEvent]
  given DbCodec[OutboxRecord] = DbCodec.derived
  given DbCodec[Instant] = DbCodec.InstantCodec

  override def findById(id: UserId): Task[Option[UserIndexRecord]] =
    xa.connect:
      sql"SELECT id, email, phone, login FROM user_index WHERE id = $id"
        .query[UserIndexRecord].run().headOption

  override def findByEmail(email: Email): Task[Option[UserIndexRecord]] =
    xa.connect:
      sql"SELECT id, email, phone, login FROM user_index WHERE email = $email"
        .query[UserIndexRecord].run().headOption

  override def findByPhone(phone: Phone): Task[Option[UserIndexRecord]] =
    xa.connect:
      sql"SELECT id, email, phone, login FROM user_index WHERE phone = $phone"
        .query[UserIndexRecord].run().headOption

  override def findByLogin(login: Login): Task[Option[UserIndexRecord]] =
    xa.connect:
      sql"SELECT id, email, phone, login FROM user_index WHERE login = $login"
        .query[UserIndexRecord].run().headOption

  private def upsertSql(id: UserId, email: Option[Email], phone: Option[Phone], login: Option[Login])(using DbCon): Unit =
    sql"""INSERT INTO user_index (id, email, phone, login)
          VALUES ($id, $email, $phone, $login)
          ON CONFLICT (id) DO UPDATE SET
            email = EXCLUDED.email,
            phone = EXCLUDED.phone,
            login = EXCLUDED.login""".update.run()
    ()

  private def patchSql(
      id: UserId,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
  )(using DbCon): Unit =
    val (updEmail, valEmail) = email.toUpdate
    val (updPhone, valPhone) = phone.toUpdate
    val (updLogin, valLogin) = login.toUpdate
    sql"""UPDATE user_index SET
            email = CASE WHEN $updEmail THEN $valEmail ELSE email END,
            phone = CASE WHEN $updPhone THEN $valPhone ELSE phone END,
            login = CASE WHEN $updLogin THEN $valLogin ELSE login END
          WHERE id = $id""".update.run()
    ()

  private def enqueueEventSql(id: UUID, event: OutboxEvent, now: Instant)(using DbCon): Unit =
    sql"""INSERT INTO user_outbox (id, event_type, payload, attempts, next_attempt_at)
          VALUES ($id, ${event.eventType}, $event::jsonb, 0, $now)""".update.run()
    ()

  override def create(
      id: UserId,
      email: Option[Email],
      phone: Option[Phone],
      login: Option[Login],
      claims: Json.Obj,
  ): IO[UserConflict | Throwable, Unit] =
    (for
      eventId <- secureRandom.nextUUIDv7
      now <- Clock.instant
      _ <- xa.transact:
             upsertSql(id, email, phone, login)
             enqueueEventSql(eventId, OutboxEvent.CreateUser(id, email, phone, login, claims), now)
    yield ()).mapError:
      case e if PostgresUserRepository.isUniqueViolation(e) => UserConflict
      case e                                                => e

  override def patch(
      id: UserId,
      email: Option[Patch[Email]],
      phone: Option[Patch[Phone]],
      login: Option[Patch[Login]],
      claims: Option[Json.Obj],
  ): Task[Unit] =
    for
      eventId <- secureRandom.nextUUIDv7
      now <- Clock.instant
      _ <- xa.transact:
        patchSql(id, email, phone, login)
        enqueueEventSql(eventId, OutboxEvent.PatchUser(id, email, phone, login, claims), now)
    yield ()

  override def insertRole(
      userId: UserId,
      tenantId: TenantId,
      roleId: RoleId,
  ): Task[Unit] =
    for
      eventId <- secureRandom.nextUUIDv7
      now <- Clock.instant
      _ <- xa.connect:
        enqueueEventSql(eventId, OutboxEvent.AssignRole(userId, tenantId, roleId), now)
    yield ()

  override def deleteRole(
      userId: UserId,
      tenantId: TenantId,
      roleId: RoleId,
  ): Task[Unit] =
    for
      eventId <- secureRandom.nextUUIDv7
      now <- Clock.instant
      _ <- xa.connect:
        enqueueEventSql(eventId, OutboxEvent.RemoveRole(userId, tenantId, roleId), now)
    yield ()

  /** Atomically claims a batch by pushing `next_attempt_at` forward by `leaseSeconds`.
    * Other instances skip claimed rows thanks to `FOR UPDATE SKIP LOCKED`.
    * If the processor crashes mid-dispatch the lease expires and the row becomes eligible again.
    */
  override def claimDueEvents(limit: Int, lease: Duration): Task[Vector[OutboxRecord]] =
    val leaseSeconds = lease.toSeconds
    xa.connect:
      sql"""UPDATE user_outbox SET
              next_attempt_at = NOW() + ($leaseSeconds || ' seconds')::interval
            WHERE id IN (
              SELECT id FROM user_outbox
              WHERE next_attempt_at <= NOW()
              ORDER BY id
              LIMIT $limit
              FOR UPDATE SKIP LOCKED
            )
            RETURNING id, payload, attempts"""
        .returning[OutboxRecord]
        .run()

  override def deleteEvent(id: UUID): Task[Unit] =
    xa.connect:
      sql"DELETE FROM user_outbox WHERE id = $id".update.run()
    .unit

  override def rescheduleEvent(id: UUID, delay: Duration): Task[Unit] =
    val seconds = delay.toSeconds
    xa.connect:
      sql"""UPDATE user_outbox SET
              attempts = attempts + 1,
              next_attempt_at = NOW() + ($seconds || ' seconds')::interval
            WHERE id = $id""".update.run()
    .unit

object PostgresUserRepository:
  val live: ZLayer[TransactorZIO & SecureRandom, Nothing, UserRepository] =
    ZLayer.fromFunction(PostgresUserRepository(_, _))

  private val UniqueViolationSqlState = "23505"

  private def isUniqueViolation(t: Throwable): Boolean = t match
    case sql: SQLException => sql.getSQLState == UniqueViolationSqlState
    case _                 => Option(t.getCause).exists(isUniqueViolation)
