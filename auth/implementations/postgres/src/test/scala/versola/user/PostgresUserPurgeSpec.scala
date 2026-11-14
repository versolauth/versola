package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.{Task, ZIO, ZLayer}

import java.util.UUID

/** Runs [[UserPurgeSpec]] against the tables of `auth`: the seed and the count of every table keyed by the user. */
object PostgresUserPurgeSpec extends PostgresSpec, UserPurgeSpec:

  import UserPurgeSpec.*

  private def seed(xa: TransactorZIO, identity: Identity): Task[Unit] =
    val id: UUID = identity.userId
    val email = identity.email
    val phone = identity.phone
    val tag = identity.tag
    val bytes = tag.getBytes
    xa.connect:
      sql"""INSERT INTO users (id, email, phone, claims) VALUES ($id, $email, $phone, '{}'::jsonb)""".update.run()
      sql"""INSERT INTO user_roles (user_id, tenant_id, role_id) VALUES ($id, 'default', 'user')""".update.run()
      sql"""INSERT INTO user_consents (user_id, client_id, scope, granted_at) VALUES ($id, 'app', '{openid}', NOW())""".update.run()
      sql"""INSERT INTO user_agents (id, user_id, created_at, expires_at) VALUES ($id, $id, NOW(), NOW() + INTERVAL '1 day')""".update.run()
      sql"""INSERT INTO sso_sessions (id, public_id, clients, user_id, user_agent_id, created_at, amr, expires_at)
            VALUES ($bytes, $tag, '[]'::jsonb, $id, $id, NOW(), '[]'::jsonb, NOW() + INTERVAL '1 day')""".update.run()
      sql"""INSERT INTO refresh_tokens (id, family_id, session_id, public_session_id, user_id, client_id, audience, scope,
              issued_at, expires_at, amr, auth_time)
            VALUES ($bytes, $tag, $bytes, $tag, $id, 'app', '{}', '{openid}', NOW(), NOW() + INTERVAL '1 day', '[]'::jsonb, NOW())""".update.run()
      sql"""INSERT INTO authorization_codes (code, client_id, user_id, session_id, public_session_id, redirect_uri, scope, resources,
              expires_at, used, access_token, family_id, amr, auth_time)
            VALUES ($bytes, 'app', $id, $bytes, $tag, 'https://app.example/cb', '{openid}', '{}', NOW() + INTERVAL '5 min',
              FALSE, $bytes, $tag, '[]'::jsonb, NOW())""".update.run()
      sql"""INSERT INTO auth_conversations (id, client_id, redirect_uri, scope, user_id, step, response_type, auth_flow, version, amr,
              needs_password_change, csrf_token, resources, expires_at, prompt_consent, response_mode)
            VALUES ($id, 'app', 'https://app.example/cb', '{openid}', $id, '{}'::json, 'code', '{}'::jsonb, 1, '[]'::jsonb,
              FALSE, $tag, '{}', NOW() + INTERVAL '15 min', FALSE, 'query')""".update.run()
      // Started from this user's session rather than by this user: dies with the session.
      sql"""INSERT INTO auth_conversations (id, client_id, redirect_uri, scope, prior_session_user_id, step, response_type, auth_flow,
              version, amr, needs_password_change, csrf_token, resources, expires_at, prompt_consent, response_mode)
            VALUES (gen_random_uuid(), 'app', 'https://app.example/cb', '{openid}', $id, '{}'::json, 'code', '{}'::jsonb, 1, '[]'::jsonb,
              FALSE, $tag, '{}', NOW() + INTERVAL '15 min', FALSE, 'query')""".update.run()
      sql"""INSERT INTO passkeys (id, user_id, public_key, signature_counter, device_type, backed_up, backup_eligible, created_at, updated_at)
            VALUES ($bytes, $id, $bytes, 0, 'MultiDevice', TRUE, TRUE, NOW(), NOW())""".update.run()
      sql"""INSERT INTO user_passwords (user_id, password, salt, created_at) VALUES ($id, $bytes, $bytes, NOW())""".update.run()
      // Throttling knows the user as whichever identifier the challenge went to, or as the id.
      sql"""INSERT INTO challenge_throttle (subject, tenant_id, challenge_type, attempts, expires_at)
            VALUES (${id.toString}, 'default', 'otp', '[]'::jsonb, NOW() + INTERVAL '1 hour'),
                   ($email, 'default', 'otp', '[]'::jsonb, NOW() + INTERVAL '1 hour'),
                   ($phone, 'default', 'password', '[]'::jsonb, NOW() + INTERVAL '1 hour')""".update.run()
    .unit

  /** Rows the tables still hold for the identity, keyed by table. */
  private def rowsOf(xa: TransactorZIO, identity: Identity): Task[Map[String, Long]] =
    val id: UUID = identity.userId
    val email = identity.email
    val phone = identity.phone
    xa.connect:
      Map(
        "users" -> sql"SELECT count(*) FROM users WHERE id = $id".query[Long].run().head,
        "user_roles" -> sql"SELECT count(*) FROM user_roles WHERE user_id = $id".query[Long].run().head,
        "user_consents" -> sql"SELECT count(*) FROM user_consents WHERE user_id = $id".query[Long].run().head,
        "user_agents" -> sql"SELECT count(*) FROM user_agents WHERE user_id = $id".query[Long].run().head,
        "sso_sessions" -> sql"SELECT count(*) FROM sso_sessions WHERE user_id = $id".query[Long].run().head,
        "refresh_tokens" -> sql"SELECT count(*) FROM refresh_tokens WHERE user_id = $id".query[Long].run().head,
        "authorization_codes" -> sql"SELECT count(*) FROM authorization_codes WHERE user_id = $id".query[Long].run().head,
        "auth_conversations" ->
          sql"SELECT count(*) FROM auth_conversations WHERE user_id = $id OR prior_session_user_id = $id".query[Long].run().head,
        "passkeys" -> sql"SELECT count(*) FROM passkeys WHERE user_id = $id".query[Long].run().head,
        "user_passwords" -> sql"SELECT count(*) FROM user_passwords WHERE user_id = $id".query[Long].run().head,
        "challenge_throttle" ->
          sql"SELECT count(*) FROM challenge_throttle WHERE subject IN (${id.toString}, $email, $phone)".query[Long].run().head,
      )

  private class PostgresFixtures(xa: TransactorZIO) extends Fixtures:
    override def seed(identity: Identity): Task[Unit] = PostgresUserPurgeSpec.seed(xa, identity)

    override def rowsOf(identity: Identity): Task[Map[String, Long]] = PostgresUserPurgeSpec.rowsOf(xa, identity)

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresUserRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect:
        sql"""TRUNCATE TABLE users, user_agents, sso_sessions, refresh_tokens, authorization_codes, auth_conversations,
              passkeys, user_passwords, challenge_throttle CASCADE""".update.run()
    yield ()
