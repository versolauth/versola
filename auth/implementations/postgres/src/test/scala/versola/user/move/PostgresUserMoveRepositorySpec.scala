package versola.user.move

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import versola.user.move.UserMoveDocument.*
import versola.user.move.UserMoveRepository.Written
import versola.user.move.UserMoveRepositorySpec.*
import versola.util.postgres.{BasicCodecs, PostgresSpec}
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.test.*
import zio.{Chunk, Task, ZIO, ZLayer}

import java.time.Instant
import java.util.UUID

object PostgresUserMoveRepositorySpec extends PostgresSpec, UserMoveRepositorySpec, BasicCodecs:

  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[Chunk[Byte]] = DbCodec.ByteArrayCodec.biMap(Chunk.fromArray, _.toArray)

  private def textArray(value: Option[List[String]]): Frag =
    sql"(SELECT ARRAY(SELECT jsonb_array_elements_text(v.j)) FROM (SELECT ${value.map(list => Json.Arr(list.map(Json.Str(_))*).toJson)}::jsonb AS j) v WHERE v.j IS NOT NULL)"

  private def textArray(value: List[String]): Frag = textArray(Some(value))

  private def jsonbArray(value: Option[List[Json]]): Frag =
    sql"(SELECT ARRAY(SELECT jsonb_array_elements(v.j)) FROM (SELECT ${value.map(list => Json.Arr(list*).toJson)}::jsonb AS j) v WHERE v.j IS NOT NULL)"

  private given JsonBDbCodec[Json] = jsonBCodec[Json]

  /** The rows as the document says, written by plain SQL, so that nothing about what the repository reads depends on what it writes. */
  private class PostgresFixtures(xa: TransactorZIO) extends Fixtures:
    override def seed(document: UserMoveDocument): Task[Unit] =
      xa.transact:
        val u = document.user
        val userId = document.userId
        sql"""INSERT INTO users (id, email, phone, login, claims, ui_locales, last_version)
              VALUES (${u.id}, ${u.email}, ${u.phone}, ${u.login}, ${u.claims.toJson}::jsonb, ${textArray(
            u.uiLocales,
          )}, ${u.lastVersion})""".update.run()
        document.passwords.foreach: p =>
          sql"""INSERT INTO user_passwords (id, user_id, password, salt, created_at, expires_at)
                VALUES (${p.id}, $userId, ${p.password}, ${p.salt}, ${p.createdAt}, ${p.expiresAt})""".update.run()
        // As the rows of this table are in a cell that has been running: the sequence is past them.
        sql"SELECT setval(pg_get_serial_sequence('user_passwords', 'id'), GREATEST(nextval(pg_get_serial_sequence('user_passwords', 'id')), ${document.passwords.map(_.id).maxOption.getOrElse(0L)}))"
          .query[Long].run()
        document.passkeys.foreach: p =>
          sql"""INSERT INTO passkeys (id, user_id, public_key, signature_counter, device_type, backed_up, backup_eligible, transports,
                                      attestation_object, client_data_json, aaguid, name, last_used_at, created_at, updated_at)
                VALUES (${p.id}, $userId, ${p.publicKey}, ${p.signatureCounter}, ${p.deviceType}, ${p.backedUp}, ${p.backupEligible},
                        ${textArray(p.transports)}, ${p.attestationObject}, ${p.clientDataJson}, ${p.aaguid}, ${p.name}, ${p.lastUsedAt},
                        ${p.createdAt}, ${p.updatedAt})""".update.run()
        document.roles.foreach: r =>
          sql"INSERT INTO user_roles (user_id, tenant_id, role_id) VALUES ($userId, ${r.tenantId}, ${r.roleId})".update.run()
        document.consents.foreach: c =>
          sql"""INSERT INTO user_consents (user_id, client_id, scope, granted_at, expires_at)
                VALUES ($userId, ${c.clientId}, ${textArray(c.scope)}, ${c.grantedAt}, ${c.expiresAt})""".update.run()
        document.refreshTokens.foreach: t =>
          sql"""INSERT INTO refresh_tokens (id, family_id, rotated_at, idempotency_key, session_id, public_session_id, user_id, client_id,
                                            audience, scope, issued_at, expires_at, requested_claims, ui_locales, nonce, acr, amr, auth_time,
                                            cnf, authorization_details)
                VALUES (${t.id}, ${t.familyId}, ${t.rotatedAt}, ${t.idempotencyKey}, ${t.sessionId}, ${t.publicSessionId}, $userId, ${t.clientId},
                        ${textArray(t.audience)}, ${textArray(t.scope)}, ${t.issuedAt}, ${t.expiresAt}, ${t.requestedClaims.map(_.toJson)}::jsonb,
                        ${textArray(t.uiLocales)}, ${t.nonce}, ${t.acr}, ${t.amr.toJson}::jsonb, ${t.authTime}, ${t.cnf.map(_.toJson)}::jsonb,
                        ${jsonbArray(t.authorizationDetails)})""".update.run()
      .unit

    override def addSession(userId: UUID): Task[Unit] =
      xa.connect:
        sql"""INSERT INTO sso_sessions (id, public_id, clients, user_id, user_agent_id, created_at, amr, expires_at, idle_expires_at)
              VALUES (${Chunk.fill[Byte](32)(
            9,
          )}, ${"kz-1.sess"}, '[]'::jsonb, $userId, ${UUID.randomUUID()}, ${now}, '{}'::jsonb, ${tokenExpiry}, NULL)"""
          .update.run()
      .unit

    override def rowsOf(userId: UUID): Task[Int] =
      xa.connect:
        sql"""SELECT (SELECT COUNT(*) FROM users WHERE id = $userId)
                   + (SELECT COUNT(*) FROM user_passwords WHERE user_id = $userId)
                   + (SELECT COUNT(*) FROM passkeys WHERE user_id = $userId)
                   + (SELECT COUNT(*) FROM user_roles WHERE user_id = $userId)
                   + (SELECT COUNT(*) FROM user_consents WHERE user_id = $userId)
                   + (SELECT COUNT(*) FROM refresh_tokens WHERE user_id = $userId)
                   + (SELECT COUNT(*) FROM sso_sessions WHERE user_id = $userId)""".query[Long].run().head.toInt

    override def revocations: Task[Int] =
      xa.connect(sql"SELECT COUNT(*) FROM auth_revocations".query[Long].run().head.toInt)

    override def tombstones: Task[List[(Chunk[Byte], String, Instant)]] =
      xa.connect(sql"SELECT id, to_cell, expires_at FROM departed_refresh_tokens ORDER BY id".query[(Chunk[Byte], String, Instant)].run().toList)

    override def tombstoneClients: Task[List[(Chunk[Byte], String, String)]] =
      xa.connect(sql"SELECT id, to_cell, client_id FROM departed_refresh_tokens ORDER BY id".query[(Chunk[Byte], String, String)].run().toList)

    override def tombstone(id: Chunk[Byte], toCell: String, clientId: String, expiresAt: Instant): Task[Unit] =
      xa.connect(
        sql"INSERT INTO departed_refresh_tokens (id, to_cell, client_id, expires_at) VALUES ($id, $toCell, $clientId, $expiresAt)".update.run(),
      ).unit

    override def arrival(userId: UUID): Task[Option[(UUID, String, String)]] =
      xa.connect(sql"SELECT move_id, from_cell, state FROM user_arrivals WHERE user_id = $userId".query[(UUID, String, String)].run().headOption)

    override def seedArrival(userId: UUID, moveId: UUID, from: String, state: String, at: Instant): Task[Unit] =
      xa.connect(
        sql"""INSERT INTO user_arrivals (user_id, move_id, from_cell, state, at) VALUES ($userId, $moveId, $from, $state, $at)
              ON CONFLICT (user_id) DO UPDATE SET move_id = excluded.move_id, from_cell = excluded.from_cell, state = excluded.state, at = excluded.at"""
          .update.run(),
      ).unit

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresUserMoveRepository(xa), PostgresUserDepartureRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"""TRUNCATE TABLE users, user_passwords, passkeys, user_roles, user_consents, refresh_tokens, sso_sessions,
                                          user_departures, departed_refresh_tokens, user_arrivals, auth_revocations CASCADE""".update.run())
    yield ()

  /** What only this storage promises: the sequence that gives `user_passwords` its ids is moved past the ids a user arrives with, or the
    * next password written in this cell would be given one of them. */
  override def implementationCases(env: Env) =
    List(
      test("a password written after an import is given an id past the ones the import kept") {
        for
          xa <- ZIO.service[TransactorZIO]
          written <- env.moves.write(documentOne.copy(passwords = documentOne.passwords.map(p => p.copy(id = p.id + 1000))), "kz-1", now)
          next <- xa.connect(
            sql"""INSERT INTO user_passwords (user_id, password, salt, created_at, expires_at)
                  VALUES (${userOne}, ${Chunk.fill[Byte](8)(1)}, ${Chunk.fill[Byte](8)(2)}, ${now}, NULL) RETURNING id""".query[Long].run().head,
          )
        yield assertTrue(written == Written.Inserted, next > 1009L)
      },
    )
