package versola.oauth.challenge.password

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.PasswordRecord
import versola.oauth.challenge.password.model.{PasswordHistoryChanged, PasswordReuseError}
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import versola.util.{Salt, Secret}
import zio.prelude.EqualOps
import zio.{Clock, IO, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresPasswordRepository(xa: TransactorZIO) extends PasswordRepository, BasicCodecs:

  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[Salt] = DbCodec.ByteArrayCodec.biMap(Salt(_), identity[Array[Byte]])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PasswordRecord] = DbCodec.derived[PasswordRecord]

  // Note: this read-only listing orders by created_at (the logical "password set" time) for
  // display. The reuse/prune read in `create` deliberately orders by id (physical commit order)
  // instead, because it must stay correct under concurrent changes where created_at can be
  // non-monotonic relative to insert order. The two only diverge under clock skew.
  override def list(userId: UserId): Task[Vector[PasswordRecord]] =
    xa.connectMeasured("list-passwords"):
      sql"""
        SELECT id, user_id, password, salt, created_at, expires_at
        FROM user_passwords
        WHERE user_id = $userId
        ORDER BY created_at DESC, id DESC
      """.query[PasswordRecord]
        .run()

  override def create(
      userId: UserId,
      password: Secret,
      salt: Salt,
      historySize: Int,
      numDifferent: Int,
      expectedHead: Option[Long],
  ): IO[Throwable | PasswordReuseError | PasswordHistoryChanged.type, Unit] =
    for
      now <- Clock.instant
      result <- xa.transactMeasured("create-password") {
        // Serialise concurrent password changes for the same user. The transaction runs at
        // READ COMMITTED, so FOR UPDATE alone cannot stop a second, concurrent change from
        // missing the not-yet-visible insert of the first (phantom read); it also would not
        // lock anything when the user has no history yet. Taking a per-user advisory lock as a
        // separate statement before the history SELECT forces the second transaction to wait,
        // then read a snapshot that already includes the first transaction's committed insert.
        // The lock is released automatically on commit/rollback.
        //
        // Why an advisory lock and not the unique-index + ON CONFLICT + FOR UPDATE pattern used
        // in challenge throttling: that pattern needs a single natural-key row to anchor on.
        // Password history has no such anchor (two different passwords are both valid rows) and
        // the empty-history case has nothing to lock, so we serialise on the user id instead.
        //
        // The SELECT result (a single dummy row) is intentionally ignored; we call it only to
        // acquire the lock.
        sql"SELECT 1 FROM pg_advisory_xact_lock(${PostgresPasswordRepository.PasswordHistoryLockNamespace}, hashtext(${userId.toString}))"
          .query[Int].run()

        // Only permanent passwords count toward history and reuse checks.
        // Order by id (SERIAL), not created_at: id is assigned at INSERT inside the serialized
        // section, so it reflects the true commit order. `now` is captured before the advisory
        // lock, so created_at values of two contending changes can be inverted relative to commit
        // order — ordering by id keeps reuse/prune correct under contention.
        val permanents = sql"""
          SELECT id, user_id, password, salt, created_at, expires_at
          FROM user_passwords
          WHERE user_id = $userId AND expires_at IS NULL
          ORDER BY id DESC
        """.query[PasswordRecord].run()

        // The service checked reuse before this transaction, so a change that committed since then
        // makes that check stale: refuse, and the service checks again against the new history.
        if permanents.headOption.map(_.id) != expectedHead then Left(PasswordHistoryChanged)
        else {
          // Remove any temporary passwords so they are never part of history
          sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL
           """.update.run()

          // A permanent password ends the first login: the link must not open an account that has one.
          sql"""DELETE FROM one_time_links WHERE user_id = $userId AND purpose = ${PostgresPasswordRepository.FirstLogin}""".update.run()

          sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
              VALUES ($userId, $password, $salt, $now, NULL)
           """.update.run()

          val toDelete = permanents.drop(historySize - 1)
          if toDelete.nonEmpty then
            val ids = toDelete.map(_.id)
            sql"""DELETE FROM user_passwords WHERE id = ANY($ids)""".update.run()
          Right(())
        }
      }.absolve
    yield result

  override def createTemporary(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      expiresAt: Instant,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("create-temporary-password") {
        // Remove any existing temporary for this user (at most one temp at a time)
        sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL
           """.update.run()

        // A reset replaces the credential the first-login link was issued with, so it revokes the link too:
        // whoever resets because the link leaked must not leave it working for the rest of the 24 hours.
        sql"""DELETE FROM one_time_links WHERE user_id = $userId AND purpose = ${PostgresPasswordRepository.FirstLogin}""".update.run()

        sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
              VALUES ($userId, $passwordHash, $salt, $now, $expiresAt)
           """.update.run()
      }.unit

  override def deleteTemporary(userId: UserId): Task[Unit] =
    xa.transactMeasured("delete-temporary-password") {
      sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL
         """.update.run()
      sql"""DELETE FROM one_time_links WHERE user_id = $userId AND purpose = ${PostgresPasswordRepository.FirstLogin}""".update.run()
    }.unit

  override def createBootstrapCredential(
      userId: UserId,
      passwordHash: Secret,
      salt: Salt,
      expiresAt: Instant,
      linkTokenHash: Option[Array[Byte]],
  ): Task[Boolean] =
    Clock.instant.flatMap: now =>
      xa.transactMeasured("create-bootstrap-credential") {
        // Same per-user lock as `create`: replicas that bootstrap at the same moment queue here, and the
        // later ones read the first one's committed rows and find a usable credential.
        sql"SELECT 1 FROM pg_advisory_xact_lock(${PostgresPasswordRepository.PasswordHistoryLockNamespace}, hashtext(${userId.toString}))"
          .query[Int].run()

        val usable = sql"""
          SELECT id FROM user_passwords
          WHERE user_id = $userId AND (expires_at IS NULL OR expires_at > $now)
        """.query[Long].run()

        if usable.nonEmpty then false
        else
          // An expired temporary password (and its token) is replaced, not kept.
          sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL""".update.run()
          sql"""DELETE FROM one_time_links WHERE user_id = $userId AND purpose = ${PostgresPasswordRepository.FirstLogin}""".update.run()
          sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
                VALUES ($userId, $passwordHash, $salt, $now, $expiresAt)""".update.run()
          linkTokenHash.foreach: hash =>
            sql"""INSERT INTO one_time_links (user_id, purpose, token_hash, created_at, expires_at)
                  VALUES ($userId, ${PostgresPasswordRepository.FirstLogin}, $hash, $now, $expiresAt)""".update.run()
          true
      }

  override def resetBootstrapCredential(userId: UserId): Task[Boolean] =
    xa.transactMeasured("reset-bootstrap-credential") {
      sql"SELECT 1 FROM pg_advisory_xact_lock(${PostgresPasswordRepository.PasswordHistoryLockNamespace}, hashtext(${userId.toString}))"
        .query[Int].run()

      val permanent = sql"SELECT id FROM user_passwords WHERE user_id = $userId AND expires_at IS NULL".query[Long].run()
      if permanent.nonEmpty then false
      else
        sql"""DELETE FROM user_passwords WHERE user_id = $userId AND expires_at IS NOT NULL""".update.run()
        sql"""DELETE FROM one_time_links WHERE user_id = $userId AND purpose = ${PostgresPasswordRepository.FirstLogin}""".update.run()
        true
    }

  override def consumeLink(tokenHash: Array[Byte]): Task[Option[ConsumedLink]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("consume-one-time-link"):
        // Only purposes this release knows are touched: a row of a purpose from a newer release is left for it.
        val known = OneTimeLinkPurpose.values.map(_.value).toVector
        sql"""
          DELETE FROM one_time_links
          WHERE token_hash = $tokenHash AND expires_at > $now AND purpose = ANY($known)
            -- Belt and braces: a permanent password ends the first login even if a release that did not know
            -- the table set it (rollback, then roll forward) and left the link behind.
            AND (purpose <> ${PostgresPasswordRepository.FirstLogin} OR NOT EXISTS (
              SELECT 1 FROM user_passwords p
              WHERE p.user_id = one_time_links.user_id AND p.expires_at IS NULL
            ))
          RETURNING user_id, purpose
        """.query[(UserId, String)].run().headOption.flatMap: (userId, purpose) =>
          OneTimeLinkPurpose.fromValue(purpose).map(ConsumedLink(userId, _))

object PostgresPasswordRepository:
  private[password] val FirstLogin: String = OneTimeLinkPurpose.FirstLogin.value

  /** Namespace (first key) for the transaction-level advisory lock guarding password history
    * changes. Advisory-lock keys are global to the entire database — shared across every table,
    * feature, and service on it — so this value must be unique DB-wide, not just within this
    * module. It is `92` (the issue number) by convention; if more advisory locks are introduced,
    * move these into a single shared registry to guarantee uniqueness. The second key is derived
    * from the user id via `hashtext`.
    *
    * `hashtext` is 32-bit, so two distinct user ids can collide and briefly serialize each other's
    * password changes. This is an accepted trade-off: collisions are rare and only cost a short
    * wait, never a correctness issue.
    */
  private[password] val PasswordHistoryLockNamespace: Int = 92

  def live: ZLayer[TransactorZIO, Throwable, PasswordRepository] =
    ZLayer.fromFunction(PostgresPasswordRepository(_))
