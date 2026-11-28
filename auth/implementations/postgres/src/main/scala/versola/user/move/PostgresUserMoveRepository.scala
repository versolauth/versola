package versola.user.move

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.user.PostgresUserRepository
import versola.user.model.UserId
import versola.user.move.UserMoveDocument.*
import versola.user.move.UserMoveRepository.{Completed, Written}
import versola.util.postgres.BasicCodecs
import zio.json.ast.Json
import zio.{Chunk, Clock, Task, ZLayer}

import java.time.Instant
import java.util.UUID

/** The rows of a user as a [[UserMoveDocument]] and back. The array columns travel as JSON arrays (`to_jsonb` on the way out,
  * rebuilt on the way in), which keeps a NULL array apart from an empty one without a codec for each element type. */
class PostgresUserMoveRepository(xa: TransactorZIO) extends UserMoveRepository, BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[Chunk[Byte]] = DbCodec.ByteArrayCodec.biMap(Chunk.fromArray, _.toArray)
  private given DbCodec[Json] = jsonBCodec[Json]
  private given DbCodec[Json.Obj] = jsonBCodec[Json.Obj]
  private given strings: DbCodec[List[String]] = jsonBCodec[List[String]]
  private given jsons: DbCodec[List[Json]] = jsonBCodec[List[Json]]
  private given DbCodec[User] = DbCodec.derived[User]
  private given DbCodec[Password] = DbCodec.derived[Password]
  private given DbCodec[Passkey] = DbCodec.derived[Passkey]
  private given DbCodec[Role] = DbCodec.derived[Role]
  private given DbCodec[Consent] = DbCodec.derived[Consent]
  private given DbCodec[RefreshToken] = DbCodec.derived[RefreshToken]

  /** The rows of the user for the move `moveId`, those refresh tokens that have not expired at `liveAt` (all of them with none). */
  private def readRows(userId: UUID, moveId: UUID, liveAt: Option[Instant])(using DbCon): Option[UserMoveDocument] =
    sql"""SELECT id, email, phone, login, claims, to_jsonb(ui_locales), last_version FROM users WHERE id = $userId"""
      .query[User].run().headOption.map: user =>
        UserMoveDocument(
          version = Version,
          userId = userId,
          moveId = moveId,
          user = user,
          passwords =
            sql"SELECT id, password, salt, created_at, expires_at FROM user_passwords WHERE user_id = $userId ORDER BY id".query[
              Password,
            ].run().toList,
          passkeys = sql"""SELECT id, public_key, signature_counter, device_type, backed_up, backup_eligible, to_jsonb(transports),
                                  attestation_object, client_data_json, aaguid, name, last_used_at, created_at, updated_at
                           FROM passkeys WHERE user_id = $userId ORDER BY created_at, id""".query[Passkey].run().toList,
          roles = sql"SELECT tenant_id, role_id FROM user_roles WHERE user_id = $userId ORDER BY tenant_id, role_id".query[Role].run().toList,
          consents = sql"SELECT client_id, to_jsonb(scope), granted_at, expires_at FROM user_consents WHERE user_id = $userId ORDER BY client_id"
            .query[Consent].run().toList,
          refreshTokens = sql"""SELECT id, family_id, rotated_at, idempotency_key, session_id, public_session_id, client_id,
                                       to_jsonb(audience), to_jsonb(scope), issued_at, expires_at, requested_claims, to_jsonb(ui_locales),
                                       nonce, acr, amr, auth_time, cnf, to_jsonb(authorization_details)
                                FROM refresh_tokens
                                WHERE user_id = $userId AND ($liveAt::timestamptz IS NULL OR expires_at > $liveAt::timestamptz)
                                ORDER BY issued_at, id""".query[RefreshToken].run().toList,
        )

  override def read(userId: UserId, moveId: UUID): Task[Option[UserMoveDocument]] =
    Clock.instant.flatMap: now =>
      // One snapshot for every table: a write that lands between two reads must not be half in the document.
      xa.repeatableRead.transactMeasured("read-user-for-move")(readRows(userId, moveId, Some(now)))

  // A text[] rebuilt from the JSON array a document carries; NULL for a NULL. `j` is a column of a one-row subquery so that the
  // array is built from it once and a NULL gives no row, which a scalar subquery reads as NULL.
  private def textArray(value: Option[List[String]]): Frag =
    sql"(SELECT ARRAY(SELECT jsonb_array_elements_text(v.j)) FROM (SELECT $value::jsonb AS j) v WHERE v.j IS NOT NULL)"

  private def textArray(value: List[String]): Frag = textArray(Some(value))

  private def jsonbArray(value: Option[List[Json]]): Frag =
    sql"(SELECT ARRAY(SELECT jsonb_array_elements(v.j)) FROM (SELECT $value::jsonb AS j) v WHERE v.j IS NOT NULL)"

  private val NextPasswordId = sql"nextval(pg_get_serial_sequence('user_passwords', 'id'))"

  private def exists(query: Frag)(using DbCon): Boolean = query.query[Int].run().nonEmpty

  /** Whether an identifier, a passkey or a token of the document is held by a user other than the one it is for. */
  private def clash(document: UserMoveDocument)(using DbCon): Boolean =
    val user = document.user
    val id = document.userId
    user.email.exists(email => exists(sql"SELECT 1 FROM users WHERE email = $email AND id <> $id")) ||
    user.phone.exists(phone => exists(sql"SELECT 1 FROM users WHERE phone = $phone AND id <> $id")) ||
    user.login.exists(login => exists(sql"SELECT 1 FROM users WHERE login = $login AND id <> $id")) ||
    document.passkeys.exists(passkey => exists(sql"SELECT 1 FROM passkeys WHERE id = ${passkey.id} AND user_id <> $id")) ||
    document.refreshTokens.exists(token => exists(sql"SELECT 1 FROM refresh_tokens WHERE id = ${token.id} AND user_id <> $id"))

  /** The arrival of a user: the move and its state, when there is one. A state this build does not know reads as none that may be
    * replaced (the caller compares with [[ArrivalState.Pending]]). */
  private def findArrival(userId: UUID)(using DbCon): Option[(UUID, String, String)] =
    sql"SELECT move_id, from_cell, state FROM user_arrivals WHERE user_id = $userId".query[(UUID, String, String)].run().headOption

  /** One import or discard of a user at a time: what is read below and what is written after it are one decision. */
  private def lock(userId: UUID)(using DbCon): Unit =
    sql"SELECT 1 FROM pg_advisory_xact_lock(hashtext(${userId.toString}))".query[Int].run()
    ()

  /** The ids of `user_passwords` are a sequence of this cell, so those of another cell may be taken here by someone else's
    * password: the rows keep their ids when all are free (and the sequence is moved past them, or a later password would be
    * given one of them), and are given new ones, in the order they had, when one is not. What the ids are for is that order. */
  private def insertRows(document: UserMoveDocument)(using DbCon): Unit =
    val userId = document.userId
    val user = document.user
    sql"""INSERT INTO users (id, email, phone, login, claims, ui_locales, last_version)
          VALUES (${user.id}, ${user.email}, ${user.phone}, ${user.login}, ${user.claims}, ${textArray(user.uiLocales)}, ${user.lastVersion})
       """.update.run()
    val passwordIdsFree = document.passwords.forall(password => !exists(sql"SELECT 1 FROM user_passwords WHERE id = ${password.id}"))
    document.passwords.sortBy(_.id).foreach: password =>
      val id = if passwordIdsFree then sql"${password.id}" else NextPasswordId
      sql"""INSERT INTO user_passwords (id, user_id, password, salt, created_at, expires_at)
            VALUES ($id, $userId, ${password.password}, ${password.salt}, ${password.createdAt}, ${password.expiresAt})""".update.run()
    if passwordIdsFree && document.passwords.nonEmpty then
      val highest = document.passwords.map(_.id).max
      // Never lowers the sequence: it takes a value, and sets it to the greater of that and the highest id given.
      sql"SELECT setval(pg_get_serial_sequence('user_passwords', 'id'), GREATEST($NextPasswordId, $highest))".query[Long].run()
    document.passkeys.foreach: passkey =>
      sql"""INSERT INTO passkeys (id, user_id, public_key, signature_counter, device_type, backed_up, backup_eligible, transports,
                                  attestation_object, client_data_json, aaguid, name, last_used_at, created_at, updated_at)
            VALUES (${passkey.id}, $userId, ${passkey.publicKey}, ${passkey.signatureCounter}, ${passkey.deviceType}, ${passkey.backedUp},
                    ${passkey.backupEligible}, ${textArray(passkey.transports)}, ${passkey.attestationObject}, ${passkey.clientDataJson},
                    ${passkey.aaguid}, ${passkey.name}, ${passkey.lastUsedAt}, ${passkey.createdAt}, ${passkey.updatedAt})""".update.run()
    document.roles.foreach: role =>
      sql"INSERT INTO user_roles (user_id, tenant_id, role_id) VALUES ($userId, ${role.tenantId}, ${role.roleId})".update.run()
    document.consents.foreach: consent =>
      sql"""INSERT INTO user_consents (user_id, client_id, scope, granted_at, expires_at)
            VALUES ($userId, ${consent.clientId}, ${textArray(consent.scope)}, ${consent.grantedAt}, ${consent.expiresAt})""".update.run()
    document.refreshTokens.foreach: token =>
      sql"""INSERT INTO refresh_tokens (id, family_id, rotated_at, idempotency_key, session_id, public_session_id, user_id, client_id,
                                        audience, scope, issued_at, expires_at, requested_claims, ui_locales, nonce, acr, amr, auth_time,
                                        cnf, authorization_details)
            VALUES (${token.id}, ${token.familyId}, ${token.rotatedAt}, ${token.idempotencyKey}, ${token.sessionId}, ${token.publicSessionId},
                    $userId, ${token.clientId}, ${textArray(token.audience)}, ${textArray(token.scope)}, ${token.issuedAt}, ${token.expiresAt},
                    ${token.requestedClaims}, ${textArray(token.uiLocales)}, ${token.nonce}, ${token.acr}, ${token.amr}, ${token.authTime},
                    ${token.cnf}, ${jsonbArray(token.authorizationDetails)})""".update.run()

  override def write(document: UserMoveDocument, from: String, at: Instant): Task[Written] =
    xa.transactMeasured("write-user-from-move"):
      val userId = document.userId
      lock(userId)
      val present = exists(sql"SELECT 1 FROM users WHERE id = $userId")
      findArrival(userId) match
        // The same move again. Only a `pending` arrival whose user is here is brought up to date: a `kept` one is done with, and a
        // `pending` one with no user here means the user was deleted or moved on since, which an old envelope must not undo.
        case Some((moveId, _, state)) if moveId == document.moveId =>
          if ArrivalState.parse(state).contains(ArrivalState.Pending) && present && !clash(document) then
            PostgresUserRepository.purge(userId)
            insertRows(document)
            sql"UPDATE user_arrivals SET from_cell = $from, at = $at WHERE user_id = $userId".update.run()
            Written.Replaced
          else Written.Taken
        // Another move than the one seen, or none: a user that is here has no `pending` arrival of this move, and is not overwritten.
        case _ =>
          if present || clash(document) then Written.Taken
          else
            insertRows(document)
            // The user is here, so whatever said they had left (an earlier move out of this cell) no longer holds.
            sql"DELETE FROM user_departures WHERE user_id = $userId".update.run()
            document.refreshTokens.foreach(token => sql"DELETE FROM departed_refresh_tokens WHERE id = ${token.id}".update.run())
            sql"""INSERT INTO user_arrivals (user_id, move_id, from_cell, state, at)
                  VALUES ($userId, ${document.moveId}, $from, ${ArrivalState.Pending.value}, $at)
                  ON CONFLICT (user_id) DO UPDATE
                  SET move_id = excluded.move_id, from_cell = excluded.from_cell, state = excluded.state, at = excluded.at""".update.run()
            Written.Inserted

  override def keep(userId: UserId, moveId: UUID, at: Instant): Task[Boolean] =
    xa.transactMeasured("keep-user-arrival"):
      lock(userId)
      sql"UPDATE user_arrivals SET state = ${ArrivalState.Kept.value}, at = $at WHERE user_id = $userId AND move_id = $moveId".update.run() > 0

  override def discard(userId: UserId, moveId: UUID): Task[Option[String]] =
    xa.transactMeasured("discard-user-arrival"):
      lock(userId)
      findArrival(userId) match
        case Some((arrived, from, state)) if arrived == moveId && ArrivalState.parse(state).contains(ArrivalState.Pending) =>
          PostgresUserRepository.purge(userId)
          sql"DELETE FROM user_arrivals WHERE user_id = $userId".update.run()
          Some(from)
        case _ => None

  override def removeStaleArrivals(olderThan: Instant): Task[Int] =
    xa.transactMeasured("remove-stale-user-arrivals"):
      sql"""DELETE FROM user_arrivals WHERE at < $olderThan AND NOT EXISTS (SELECT 1 FROM users WHERE users.id = user_arrivals.user_id)"""
        .update.run()

  override def complete(userId: UserId, moveId: UUID, digest: String): Task[Completed] =
    Clock.instant.flatMap: now =>
      // One snapshot for the digest and the deletion: a row changed in between is a failure to retry, not a row lost unseen.
      xa.repeatableRead.transactMeasured("complete-user-departure"):
        sql"SELECT to_cell, state, move_id FROM user_departures WHERE user_id = $userId FOR UPDATE".query[(
            String,
            String,
            UUID,
        )].run().headOption match
          case None => Completed.NotLeaving
          // Another move's completion is not this one's.
          case Some((_, _, departing)) if departing != moveId => Completed.NotLeaving
          case Some((toCell, state, _)) if DepartureState.parse(state).contains(DepartureState.Moved) => Completed.AlreadyMoved(toCell)
          case Some((toCell, _, _)) =>
            // A user who is gone, or rows that are not what was exported, are both "changed": what travelled is not what is here.
            val changed = !readRows(userId, moveId, Some(now)).map(UserMoveDocument.digest).contains(digest)
            val tokens = sql"""INSERT INTO departed_refresh_tokens (id, to_cell, client_id, expires_at)
                               SELECT id, $toCell, client_id, expires_at FROM refresh_tokens WHERE user_id = $userId AND expires_at > $now
                               ON CONFLICT (id) DO UPDATE
                               SET to_cell = excluded.to_cell, client_id = excluded.client_id, expires_at = excluded.expires_at""".update.run()
            PostgresUserRepository.purge(userId)
            sql"UPDATE user_departures SET state = ${DepartureState.Moved.value}, at = $now WHERE user_id = $userId".update.run()
            Completed.Moved(toCell = toCell, tokens = tokens, changed = changed)

object PostgresUserMoveRepository:
  def live: ZLayer[TransactorZIO, Nothing, UserMoveRepository] =
    ZLayer.fromFunction(PostgresUserMoveRepository(_))
