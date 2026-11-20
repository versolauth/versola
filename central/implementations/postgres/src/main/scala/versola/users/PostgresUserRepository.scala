package versola.users

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.topology.CellId
import versola.central.users.{
  DirectoryIdentifier,
  IdentifierKind,
  IdentifierMac,
  OutboxEvent,
  OutboxRecord,
  UserConflict,
  UserDirectoryRecord,
  UserId,
  UserIndexConflict,
  UserNotFound,
  UserRepository,
}
import versola.util.SecureRandom
import versola.util.postgres.BasicCodecs
import zio.{Duration, IO, Schedule, Task, ZLayer}

import java.sql.SQLException
import java.util.UUID

class PostgresUserRepository(xa: TransactorZIO, secureRandom: SecureRandom) extends UserRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[OutboxEvent] = dbCodecFromJsonCodec[OutboxEvent]
  given DbCodec[OutboxRecord] = DbCodec.derived
  given DbCodec[CellId] = DbCodec.StringCodec.biMap(CellId(_), identity[String])
  given DbCodec[IdentifierMac] = DbCodec.ByteArrayCodec.biMap(IdentifierMac(_), identity[Array[Byte]])
  given DbCodec[UserDirectoryRecord] = DbCodec.derived

  override def find(id: UserId): Task[Option[UserDirectoryRecord]] =
    xa.connectMeasured("find-user-by-id"):
      sql"SELECT user_id, cell_id FROM user_directory WHERE user_id = $id".query[UserDirectoryRecord].run().headOption

  override def findByIdentifier(identifier: DirectoryIdentifier): Task[Option[UserDirectoryRecord]] =
    xa.connectMeasured("find-user-by-identifier"):
      sql"""SELECT u.user_id, u.cell_id
            FROM identifier_directory i
            JOIN user_directory u ON u.user_id = i.user_id
            WHERE i.kind = ${identifier.kind.code} AND i.mac = ${identifier.mac}"""
        .query[UserDirectoryRecord].run().headOption

  override def identifierKinds(id: UserId): Task[Set[IdentifierKind]] =
    xa.connectMeasured("find-user-identifier-kinds"):
      sql"SELECT kind FROM identifier_directory WHERE user_id = $id".query[String].run().flatMap(IdentifierKind.of).toSet

  private def insertUserSql(user: UserDirectoryRecord)(using DbCon): Unit =
    sql"INSERT INTO user_directory (user_id, cell_id) VALUES (${user.id}, ${user.cellId})".update.run()
    ()

  private def insertIdentifierSql(id: UserId, identifier: DirectoryIdentifier)(using DbCon): Unit =
    sql"INSERT INTO identifier_directory (kind, mac, user_id) VALUES (${identifier.kind.code}, ${identifier.mac}, $id)".update.run()
    ()

  private def enqueueEventSql(userId: UserId, id: UUID, event: OutboxEvent)(using DbCon): Unit =
    sql"""INSERT INTO user_outbox (id, user_id, event_type, payload, attempts, next_attempt_at)
          VALUES ($id, $userId, ${event.eventType}, $event::jsonb, 0, NOW())""".update.run()
    ()

  override def create(
      user: UserDirectoryRecord,
      identifiers: List[DirectoryIdentifier],
      event: OutboxEvent,
      eventId: UUID,
  ): IO[UserConflict | Throwable, Unit] =
    xa.transactMeasured("create-user"):
      insertUserSql(user)
      identifiers.foreach(insertIdentifierSql(user.id, _))
      enqueueEventSql(user.id, eventId, event)
    .mapError:
      case e if PostgresUserRepository.isUniqueViolation(e) => UserConflict
      case e => e

  override def claim(identifiers: List[DirectoryIdentifier], candidate: UserDirectoryRecord): IO[UserIndexConflict | Throwable, UserDirectoryRecord] =
    attemptClaim(identifiers, candidate)
      // Two claims of an identifier nobody held: one inserts it, the other fails on the primary key and, asked
      // again, reads the row that won.
      .retry(Schedule.recurWhile[Throwable](PostgresUserRepository.isUniqueViolation) && Schedule.recurs(3))
      .mapError:
        case PostgresUserRepository.IndexConflict => UserIndexConflict
        case e => e

  private def attemptClaim(identifiers: List[DirectoryIdentifier], candidate: UserDirectoryRecord): Task[UserDirectoryRecord] =
    xa.transactMeasured("claim-user-identifiers"):
      val owners = identifiers.flatMap: identifier =>
        sql"""SELECT u.user_id, u.cell_id
              FROM identifier_directory i
              JOIN user_directory u ON u.user_id = i.user_id
              WHERE i.kind = ${identifier.kind.code} AND i.mac = ${identifier.mac}
              FOR UPDATE OF i""".query[UserDirectoryRecord].run()
      owners.distinctBy(_.id) match
        case Nil =>
          insertUserSql(candidate)
          identifiers.foreach(insertIdentifierSql(candidate.id, _))
          candidate
        case owner :: Nil => owner
        case _ => throw PostgresUserRepository.IndexConflict

  override def replaceIdentifiers(
      id: UserId,
      set: List[DirectoryIdentifier],
      remove: Set[IdentifierKind],
      event: OutboxEvent,
      eventId: UUID,
  ): IO[UserNotFound | UserConflict | Throwable, Unit] =
    xa.transactMeasured("patch-user"):
      // Lock the user row to prevent lost updates and serialize events
      val locked = sql"SELECT user_id FROM user_directory WHERE user_id = $id FOR UPDATE".query[UserId].run()
      if locked.isEmpty then throw PostgresUserRepository.NotFound
      val replaced = (remove ++ set.map(_.kind)).map(_.code).toList
      if replaced.nonEmpty then sql"DELETE FROM identifier_directory WHERE user_id = $id AND kind = ANY($replaced)".update.run()
      set.foreach(insertIdentifierSql(id, _))
      enqueueEventSql(id, eventId, event)
    .mapError:
      case PostgresUserRepository.NotFound => UserNotFound
      case e if PostgresUserRepository.isUniqueViolation(e) => UserConflict
      case e => e

  override def delete(id: UserId): Task[Unit] =
    for
      eventId <- secureRandom.nextUUIDv7
      _ <- xa.transactMeasured("delete-user"):
        sql"DELETE FROM user_directory WHERE user_id = $id".update.run()
        enqueueEventSql(id, eventId, OutboxEvent.DeleteUser(id))
    yield ()

  override def enqueueRoleUpdate(
      userId: UserId,
      tenantId: TenantId,
      add: Set[RoleId],
      remove: Set[RoleId],
  ): Task[Unit] =
    for
      eventId <- secureRandom.nextUUIDv7
      _ <- xa.transactMeasured("enqueue-role-update"):
        enqueueEventSql(userId, eventId, OutboxEvent.UpdateUserRoles(userId, tenantId, add, remove))
    yield ()

  /** Atomically claims a batch by pushing `next_attempt_at` forward by `leaseSeconds`.
    * Processes events in per-user FIFO order.
    */
  override def claimDueEvents(limit: Int, lease: Duration): Task[Vector[OutboxRecord]] =
    val leaseSeconds = lease.toSeconds
    xa.connectMeasured("claim-due-events"):
      sql"""UPDATE user_outbox SET
              next_attempt_at = NOW() + ($leaseSeconds || ' seconds')::interval
            WHERE id IN (
              SELECT id FROM user_outbox u1
              WHERE next_attempt_at <= NOW()
              AND NOT EXISTS (
                SELECT 1 FROM user_outbox u2
                WHERE u2.user_id = u1.user_id
                AND u2.id < u1.id
              )
              ORDER BY id
              LIMIT $limit
              FOR UPDATE SKIP LOCKED
            )
            RETURNING id, user_id, payload, attempts"""
        .returning[OutboxRecord]
        .run()

  override def deleteEvent(id: UUID): Task[Unit] =
    xa.connectMeasured("delete-event"):
      sql"DELETE FROM user_outbox WHERE id = $id".update.run()
    .unit

  override def rescheduleEvent(id: UUID, delay: Duration): Task[Unit] =
    val seconds = delay.toSeconds
    xa.connectMeasured("reschedule-event"):
      sql"""UPDATE user_outbox SET
              attempts = attempts + 1,
              next_attempt_at = NOW() + ($seconds || ' seconds')::interval
            WHERE id = $id""".update.run()
    .unit

  override def moveToDeadLetter(id: UUID, error: String): Task[Unit] =
    xa.transactMeasured("move-event-to-dead-letter"):
      sql"""INSERT INTO user_outbox_dead (id, user_id, event_type, payload, attempts, failed_at, error)
            SELECT id, user_id, event_type, payload, attempts, NOW(), $error
            FROM user_outbox
            WHERE id = $id""".update.run()
      sql"DELETE FROM user_outbox WHERE id = $id".update.run()
    .unit

object PostgresUserRepository:
  val live: ZLayer[TransactorZIO & SecureRandom, Nothing, UserRepository] =
    ZLayer.fromFunction(PostgresUserRepository(_, _))

  private val UniqueViolationSqlState = "23505"

  private def isUniqueViolation(t: Throwable): Boolean = t match
    case sql: SQLException => sql.getSQLState == UniqueViolationSqlState
    case _ => Option(t.getCause).exists(isUniqueViolation)

  /** Raised inside the patch transaction (rolling it back) when the user row does not exist. */
  private case object NotFound extends RuntimeException("User not found in index")

  /** Raised when the supplied credentials already resolve to more than one existing user. */
  private case object IndexConflict extends RuntimeException("Registration claim resolves to multiple user IDs")
