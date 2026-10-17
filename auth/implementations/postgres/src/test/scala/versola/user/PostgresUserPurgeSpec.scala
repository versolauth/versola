package versola.user

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.user.model.UserId
import versola.util.postgres.PostgresSpec
import zio.ZIO
import zio.test.*

import java.util.UUID

/** `UserRepository.delete` has to reach every table keyed by the user, and nothing but a leftover
  * row -- a refresh token that still redeems, a passkey that still signs in -- would notice one
  * it missed. The rows are seeded raw rather than through the owning repositories: what is
  * asserted is which tables the purge reaches, not how those repositories write.
  */
object PostgresUserPurgeSpec extends PostgresSpec:

  private val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val userId2 = UserId(UUID.fromString("58c5fe37-6c39-43d1-ae8c-3fab32e1ed72"))

  private case class Identity(userId: UserId, email: String, phone: String, tag: String)

  private val one = Identity(userId1, "purge1@example.com", "+12025550001", "one")
  private val two = Identity(userId2, "purge2@example.com", "+12025550002", "two")

  private def seed(xa: TransactorZIO, identity: Identity) =
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

  /** Rows the tables still hold for the identity, keyed by table. */
  private def rowsOf(xa: TransactorZIO, identity: Identity) =
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

  private val seededRows = Map(
    "users" -> 1L,
    "user_roles" -> 1L,
    "user_consents" -> 1L,
    "user_agents" -> 1L,
    "sso_sessions" -> 1L,
    "refresh_tokens" -> 1L,
    "authorization_codes" -> 1L,
    "auth_conversations" -> 2L,
    "passkeys" -> 1L,
    "user_passwords" -> 1L,
    "challenge_throttle" -> 3L,
  )

  override def spec =
    suite("PostgresUserPurgeSpec")(
      test("delete takes the sessions, tokens, codes, conversations, passkeys, passwords, roles, consents and throttling with the account") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect:
            sql"""TRUNCATE TABLE users, user_agents, sso_sessions, refresh_tokens, authorization_codes, auth_conversations,
                  passkeys, user_passwords, challenge_throttle CASCADE""".update.run()
          _ <- seed(xa, one)
          _ <- seed(xa, two)
          before <- rowsOf(xa, one)
          _ <- PostgresUserRepository(xa).delete(one.userId)
          after <- rowsOf(xa, one)
          other <- rowsOf(xa, two)
        yield assertTrue(before == seededRows).label("the seed has to cover every table or the purge is not being tested") &&
          assertTrue(after.values.forall(_ == 0L)).label(s"rows left after the purge: ${after.filter(_._2 > 0)}") &&
          assertTrue(other == seededRows).label("the other account's rows are not for this purge to touch")
      },
    ) @@ TestAspect.sequential
