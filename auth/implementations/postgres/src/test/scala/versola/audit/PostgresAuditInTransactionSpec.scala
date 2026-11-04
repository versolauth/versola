package versola.audit

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.antifraud.*
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.PostgresAuthorizationCodeRepository
import versola.oauth.challenge.passkey.PostgresPasskeyRepository
import versola.oauth.challenge.password.PostgresPasswordRepository
import versola.oauth.client.model.{ClientId, ScopeToken, TenantId}
import versola.oauth.consent.PostgresConsentRepository
import versola.oauth.consent.model.ConsentRecord
import versola.oauth.session.model.*
import versola.oauth.session.{PostgresSessionRepository, PostgresUserAgentRepository}
import versola.role.model.RoleId
import versola.user.PostgresUserRepository
import versola.user.model.UserId
import versola.util.audit.AuditEvent
import versola.audit.AuditSupport.*
import versola.util.postgres.{AuditInsert, PostgresSpec}
import versola.util.{MAC, Salt, Secret}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID
import scala.util.Random

/** The state changes whose audit event is inserted in their own transaction, run against the real tables: for each, that the change and
  * its row come together, that a change whose event cannot be inserted does not happen, and that a call that changed nothing writes
  * no row.
  *
  * A change is made to be refused by handing it an event whose key a row already holds: the first change under an event stands, the
  * second with the same event finds the key taken, and must take its own change with it. */
object PostgresAuditInTransactionSpec extends PostgresSpec:

  private val app = ClientId("app")

  private def newId: UUID = UUID.randomUUID()
  private def mac: MAC = MAC(Array.fill(32)(Random.nextInt(256).toByte))
  private def userId: UserId = UserId(newId)
  private def public: PublicSessionId = PublicSessionId(newId.toString)
  private def family: RefreshTokenFamilyId = RefreshTokenFamilyId(newId.toString)

  private def stored(event: AuditEvent) = AuditProbe.stored(event)

  private def session(user: UserId, publicId: PublicSessionId = public, clients: List[ClientId] = List(app)) =
    SessionRecord(
      userId = user,
      clients = clients.map(ClientEntry(_, Instant.EPOCH)),
      userAgentId = UserAgentId(newId),
      createdAt = Instant.EPOCH,
      amr = Map.empty,
      publicId = publicId,
      expiresAt = Instant.EPOCH,
    )

  private def token(user: UserId, familyId: RefreshTokenFamilyId, sessionId: MAC, publicId: PublicSessionId, client: ClientId = app) =
    RefreshTokenRecord(
      familyId = familyId,
      sessionId = sessionId,
      publicSessionId = publicId,
      userId = user,
      clientId = client,
      audience = Nil,
      authorizationDetails = None,
      scope = Set(ScopeToken("read")),
      issuedAt = Instant.EPOCH,
      expiresAt = Instant.EPOCH.plusSeconds(3600),
      requestedClaims = None,
      uiLocales = None,
      nonce = None,
      amr = Set.empty,
      authTime = Instant.EPOCH,
      acr = None,
      cnf = None,
    )

  private def liveTokens(xa: TransactorZIO, familyId: RefreshTokenFamilyId) =
    val fam: String = familyId
    xa.connect(sql"SELECT COUNT(*) FROM refresh_tokens WHERE family_id = $fam AND expires_at > '1970-01-01T00:00:00Z'".query[Long].run().head)

  private def liveSessions(xa: TransactorZIO, user: UserId) =
    val id: UUID = user
    xa.connect(sql"SELECT COUNT(*) FROM sso_sessions WHERE user_id = $id AND expires_at > '1970-01-01T00:00:00Z'".query[Long].run().head)

  override def spec =
    suite("PostgresAuditInTransactionSpec")(
      suite("sessions and tokens")(
        test("login.completed: the session and its event are made together, and a session whose event is refused is not made") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            user = userId
            idA = mac
            idB = mac
            e <- AuditProbe.event(AuthAuditKinds.LoginCompleted, Some(user))
            _ <- sessions.create(idA, session(user), 1.hour, None, None, e)
            a <- sessions.findSession(idA)
            n <- stored(e)
            refused <- sessions.create(idB, session(user), 1.hour, None, None, e).either
            b <- sessions.findSession(idB)
          yield assertTrue(a.isDefined, n == 1L, refused.isLeft, b.isEmpty)
        },
        test("token.issued: the refresh token and its event are written together; with the event switched off there is none; a rotation that was lost writes none") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            user = userId
            sess = mac
            pub = public
            tokA = mac
            tokB = mac
            tokC = mac
            tokD = mac
            e <- AuditProbe.event(AuthAuditKinds.TokenIssued, Some(user))
            _ <- sessions.createRefreshToken(tokA, None, token(user, family, sess, pub), None, Some(e))
            a <- sessions.findToken(tokA)
            n <- stored(e)
            refused <- sessions.createRefreshToken(tokB, None, token(user, family, sess, pub), None, Some(e)).either
            b <- sessions.findToken(tokB)
            switchedOff <- sessions.createRefreshToken(tokC, None, token(user, family, sess, pub), None, None).either
            c <- sessions.findToken(tokC)
            lost <- AuditProbe.event(AuthAuditKinds.TokenIssued, Some(user))
            // a predecessor nobody holds: the rotation retires nothing and inserts nothing
            lostRotation <- sessions.createRefreshToken(tokD, Some(mac), token(user, family, sess, pub), None, Some(lost)).either
            nLost <- stored(lost)
          yield assertTrue(
            a.isDefined,
            n == 1L,
            refused.isLeft,
            b.isEmpty,
            switchedOff.isRight,
            c.isDefined,
            lostRotation.isLeft,
            nLost == 0L,
          )
        },
        test("token.reuse_detected: the family's revocation and its event are one transaction, with the count; a token that names no family writes none") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            user = userId
            famA = family
            famB = family
            sess = mac
            pub = public
            (a1, a2, b1, b2) = (mac, mac, mac, mac)
            _ <- sessions.createRefreshToken(a1, None, token(user, famA, sess, pub), None, None)
            _ <- sessions.createRefreshToken(a2, Some(a1), token(user, famA, sess, pub), None, None)
            _ <- sessions.createRefreshToken(b1, None, token(user, famB, sess, pub), None, None)
            _ <- sessions.createRefreshToken(b2, Some(b1), token(user, famB, sess, pub), None, None)
            e <- AuditProbe.event(AuthAuditKinds.TokenReuseDetected)
            revoked <- sessions.revokeFamily(a1, app, e)
            n <- stored(e)
            count <- AuditProbe.detail(e, "revokedCount")
            who <- AuditProbe.subject(e)
            refused <- sessions.revokeFamily(b1, app, e).either
            stillLive <- liveTokens(xa, famB)
            unknown <- AuditProbe.event(AuthAuditKinds.TokenReuseDetected)
            none <- sessions.revokeFamily(mac, app, unknown)
            nNone <- stored(unknown)
          yield assertTrue(
            revoked.map(_.familyId) == Some(famA),
            n == 1L,
            count.contains(Json.Num(1)),
            who.flatMap(_._1).contains(user: UUID),
            refused.isLeft,
            stillLive == 2L,
            none.isEmpty,
            nNone == 0L,
          )
        },
        test("token.reuse_detected for an authorization code: the family is revoked and the event written even when nothing of it is live") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            user = userId
            fam = family
            sess = mac
            pub = public
            tok = mac
            _ <- sessions.createRefreshToken(tok, None, token(user, fam, sess, pub), None, None)
            e <- AuditProbe.event(AuthAuditKinds.TokenReuseDetected, Some(user))
            _ <- sessions.deleteByFamily(fam, e)
            count <- AuditProbe.detail(e, "revokedCount")
            e2 <- AuditProbe.event(AuthAuditKinds.TokenReuseDetected, Some(user))
            _ <- sessions.deleteByFamily(fam, e2)
            count2 <- AuditProbe.detail(e2, "revokedCount")
            n2 <- stored(e2)
            live <- liveTokens(xa, fam)
            otherFam = family
            otherTok = mac
            _ <- sessions.createRefreshToken(otherTok, None, token(user, otherFam, sess, pub), None, None)
            refused <- sessions.deleteByFamily(otherFam, e).either
            otherLive <- liveTokens(xa, otherFam)
          yield assertTrue(
            count.contains(Json.Num(1)),
            count2.contains(Json.Num(0)),
            n2 == 1L,
            live == 0L,
            refused.isLeft,
            otherLive == 1L,
          )
        },
        test("token.revoked (RFC 7009): a live refresh token's revocation and its event are one transaction; repeating it writes none") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            user = userId
            sess = mac
            pub = public
            (tokA, tokB) = (mac, mac)
            _ <- sessions.createRefreshToken(tokA, None, token(user, family, sess, pub), None, None)
            _ <- sessions.createRefreshToken(tokB, None, token(user, family, sess, pub), None, None)
            e <- AuditProbe.event(AuthAuditKinds.TokenRevoked)
            _ <- sessions.delete(tokA, e)
            n <- stored(e)
            who <- AuditProbe.subject(e)
            again <- AuditProbe.event(AuthAuditKinds.TokenRevoked)
            _ <- sessions.delete(tokA, again)
            nAgain <- stored(again)
            refused <- sessions.delete(tokB, e).either
            bStill <- sessions.findToken(tokB)
          yield assertTrue(
            n == 1L,
            who.flatMap(_._1).contains(user: UUID),
            nAgain == 0L,
            refused.isLeft,
            bStill.isDefined,
          )
        },
        test("token.revoked (administrator): the client's live refresh tokens are revoked with one event carrying how many; none live writes none") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            user = userId
            sess = mac
            pub = public
            client = ClientId(newId.toString)
            _ <- ZIO.foreachDiscard(1 to 2)(_ => sessions.createRefreshToken(mac, None, token(user, family, sess, pub, client), None, None))
            e <- AuditProbe.event(AuthAuditKinds.TokenRevoked)
            revoked <- sessions.revokeRefreshTokensByUserAndClient(user, client, e)
            count <- AuditProbe.detail(e, "count")
            who <- AuditProbe.subject(e)
            again <- AuditProbe.event(AuthAuditKinds.TokenRevoked)
            none <- sessions.revokeRefreshTokensByUserAndClient(user, client, again)
            nNone <- stored(again)
            otherClient = ClientId(newId.toString)
            _ <- sessions.createRefreshToken(mac, None, token(user, family, sess, pub, otherClient), None, None)
            refused <- sessions.revokeRefreshTokensByUserAndClient(user, otherClient, e).either
          yield assertTrue(
            revoked == 2,
            count.contains(Json.Num(2)),
            who.flatMap(_._1).contains(user: UUID),
            none == 0,
            nNone == 0L,
            refused.isLeft,
          )
        },
        test("session.ended: each session a call ended gets its own row, with its public id and how many relying parties it had; an ended session writes none") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            user = userId
            (pubA, pubB) = (public, public)
            _ <- sessions.create(mac, session(user, pubA, List(app)), 1.hour, None, None, AuditProbe.sample())
            _ <- sessions.create(mac, session(user, pubB, Nil), 1.hour, None, None, AuditProbe.sample())
            e <- AuditProbe.event(AuthAuditKinds.SessionEnded)
            ended <- sessions.invalidateByUserId(user, e)
            rows <- AuditProbe.storedFor(AuthAuditKinds.SessionEnded, user)
            again <- AuditProbe.event(AuthAuditKinds.SessionEnded)
            none <- sessions.invalidateByUserId(user, again)
            rowsAfter <- AuditProbe.storedFor(AuthAuditKinds.SessionEnded, user)
          yield assertTrue(
            ended.size == 2,
            rows.map(_._2).toSet == Set(Some(pubA.toString), Some(pubB.toString)),
            rows.size == 2,
            rows.exists(r => r._2.contains(pubA.toString) && r._3.contains("\"clients\": 1")),
            rows.exists(r => r._2.contains(pubB.toString) && r._3.contains("\"clients\": 0")),
            none.isEmpty,
            rowsAfter.size == 2,
          )
        },
        test("session.ended by id, by public id and by public id for a user: one row for a live session, none for one already ended, and a refused event keeps the session") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            (userA, userB, userC, userD) = (userId, userId, userId, userId)
            (idA, idB, idC, idD) = (mac, mac, mac, mac)
            (pubA, pubB, pubC, pubD) = (public, public, public, public)
            _ <- ZIO.foreachDiscard(List((userA, idA, pubA), (userB, idB, pubB), (userC, idC, pubC), (userD, idD, pubD)))((u, id, p) =>
              sessions.create(id, session(u, p), 1.hour, None, None, AuditProbe.sample()),
            )
            e <- AuditProbe.event(AuthAuditKinds.SessionEnded)
            _ <- sessions.invalidate(idA, e)
            _ <- sessions.invalidateByPublicId(pubB, e)
            _ <- sessions.invalidateByPublicIdForUser(pubC, userC, e)
            rows <- ZIO.foreach(List(userA, userB, userC))(AuditProbe.storedFor(AuthAuditKinds.SessionEnded, _))
            // the same sessions again: each call still answers as it did, and none writes a row
            gone1 <- sessions.invalidate(idA, e)
            gone2 <- sessions.invalidateByPublicId(pubB, e)
            gone3 <- sessions.invalidateByPublicIdForUser(pubC, userC, e)
            rowsAfter <- ZIO.foreach(List(userA, userB, userC))(AuditProbe.storedFor(AuthAuditKinds.SessionEnded, _))
            // a live session whose event's key is taken: the call fails and the session is still there
            _ <- xa.transact(AuditInsert.insert(e.forSession(pubD.toString, userD, 0)))
            refused <- sessions.invalidateByPublicId(pubD, e).either
            dLive <- sessions.findSession(idD)
          yield assertTrue(
            rows.map(_.size) == List(1, 1, 1),
            rows.flatMap(_.map(_._2)) == List(Some(pubA.toString), Some(pubB.toString), Some(pubC.toString)),
            gone1.isEmpty,
            gone2.isDefined,
            gone3,
            rowsAfter.map(_.size) == List(1, 1, 1),
            refused.isLeft,
            dLive.isDefined,
          )
        },
        test("session.ended when a user agent is forgotten: the sessions it ended are events, the user agent goes with them, and a refused event keeps both") {
          for
            xa <- ZIO.service[TransactorZIO]
            sessions = PostgresSessionRepository(xa)
            agents = PostgresUserAgentRepository(xa)
            (user, userB) = (userId, userId)
            (agent, agentB) = (UserAgentId(newId), UserAgentId(newId))
            (pubA, pubB) = (public, public)
            userUuid: UUID = user
            userBUuid: UUID = userB
            agentUuid: UUID = agent
            agentBUuid: UUID = agentB
            _ <- xa.connect:
              sql"INSERT INTO user_agents (id, user_id, created_at, expires_at) VALUES ($agentUuid, $userUuid, NOW(), NOW() + INTERVAL '1 day')".update.run()
              sql"INSERT INTO user_agents (id, user_id, created_at, expires_at) VALUES ($agentBUuid, $userBUuid, NOW(), NOW() + INTERVAL '1 day')".update.run()
            idB = mac
            _ <- sessions.create(mac, session(user, pubA).copy(userAgentId = agent), 1.hour, None, None, AuditProbe.sample())
            _ <- sessions.create(idB, session(userB, pubB).copy(userAgentId = agentB), 1.hour, None, None, AuditProbe.sample())
            e <- AuditProbe.event(AuthAuditKinds.SessionEnded)
            forgotten <- agents.forget(agent, user, e)
            rows <- AuditProbe.storedFor(AuthAuditKinds.SessionEnded, user)
            // the key of the other session's event is taken: nothing of it is done
            _ <- xa.transact(AuditInsert.insert(e.forSession(pubB.toString, userB, 0)))
            refused <- agents.forget(agentB, userB, e).either
            bLive <- sessions.findSession(idB)
            unknown <- AuditProbe.event(AuthAuditKinds.SessionEnded)
            notTheirs <- agents.forget(UserAgentId(newId), user, unknown)
            nNone <- stored(unknown)
          yield assertTrue(
            forgotten,
            rows.map(_._2) == List(Some(pubA.toString)),
            refused.isLeft,
            bLive.isDefined,
            !notTheirs,
            nNone == 0L,
          )
        },
      ),
      suite("accounts")(
        test("account.registered: the account and its event are one transaction; signing in to an existing account writes none") {
          for
            xa <- ZIO.service[TransactorZIO]
            users = PostgresUserRepository(xa)
            user = userId
            email = versola.util.Email(s"${newId}@example.com")
            e <- AuditProbe.event(AuthAuditKinds.AccountRegistered, Some(user))
            created <- users.register(user, Left(email), TenantId.default, Set(RoleId("user")), e)
            n <- stored(e)
            again <- AuditProbe.event(AuthAuditKinds.AccountRegistered, Some(userId))
            existing <- users.register(userId, Left(email), TenantId.default, Set(RoleId("user")), again)
            nAgain <- stored(again)
            otherEmail = versola.util.Email(s"${newId}@example.com")
            refused <- users.register(userId, Left(otherEmail), TenantId.default, Set.empty, e).either
            absent <- users.findByCredential(Left(otherEmail))
          yield assertTrue(created.id == user, n == 1L, existing.id == user, nAgain == 0L, refused.isLeft, absent.isEmpty)
        },
        test("account.claims_updated: written with the change; an unknown account writes none; a refused event leaves the claims as they were") {
          for
            xa <- ZIO.service[TransactorZIO]
            users = PostgresUserRepository(xa)
            (userA, userB) = (userId, userId)
            _ <- ZIO.foreachDiscard(List(userA, userB))(u =>
              users.register(u, Left(versola.util.Email(s"${newId}@example.com")), TenantId.default, Set.empty, AuditProbe.sample()),
            )
            e <- AuditProbe.event(AuthAuditKinds.AccountClaimsUpdated, Some(userA))
            _ <- users.patchClaims(userA, Json.Obj("name" -> Json.Str("Ada")), e)
            n <- stored(e)
            nobody <- AuditProbe.event(AuthAuditKinds.AccountClaimsUpdated)
            _ <- users.patchClaims(userId, Json.Obj("name" -> Json.Str("Nobody")), nobody)
            nNobody <- stored(nobody)
            refused <- users.patchClaims(userB, Json.Obj("name" -> Json.Str("Bob")), e).either
            b <- users.find(userB)
          yield assertTrue(n == 1L, nNobody == 0L, refused.isLeft, b.exists(!_.claims.toString.contains("Bob")))
        },
        test("account.deleted: the account goes with its event; an unknown account writes none; a refused event keeps the account") {
          for
            xa <- ZIO.service[TransactorZIO]
            users = PostgresUserRepository(xa)
            (userA, userB) = (userId, userId)
            _ <- ZIO.foreachDiscard(List(userA, userB))(u =>
              users.register(u, Left(versola.util.Email(s"${newId}@example.com")), TenantId.default, Set.empty, AuditProbe.sample()),
            )
            e <- AuditProbe.event(AuthAuditKinds.AccountDeleted, Some(userA))
            _ <- users.delete(userA, e)
            n <- stored(e)
            gone <- users.find(userA)
            unknown <- AuditProbe.event(AuthAuditKinds.AccountDeleted)
            _ <- users.delete(userId, unknown)
            nUnknown <- stored(unknown)
            refused <- users.delete(userB, e).either
            b <- users.find(userB)
          yield assertTrue(n == 1L, gone.isEmpty, nUnknown == 0L, refused.isLeft, b.isDefined)
        },
        test("account.roles_updated: written when a role was granted or taken away; a call that changed none writes none; a refused event leaves the roles") {
          for
            xa <- ZIO.service[TransactorZIO]
            users = PostgresUserRepository(xa)
            (userA, userB) = (userId, userId)
            _ <- ZIO.foreachDiscard(List(userA, userB))(u =>
              users.register(u, Left(versola.util.Email(s"${newId}@example.com")), TenantId.default, Set.empty, AuditProbe.sample()),
            )
            grant <- AuditProbe.event(AuthAuditKinds.AccountRolesUpdated, Some(userA))
            _ <- users.updateRoles(userA, TenantId.default, Set(RoleId("editor")), Set.empty, grant)
            nGrant <- stored(grant)
            repeat <- AuditProbe.event(AuthAuditKinds.AccountRolesUpdated, Some(userA))
            _ <- users.updateRoles(userA, TenantId.default, Set(RoleId("editor")), Set.empty, repeat)
            nRepeat <- stored(repeat)
            take <- AuditProbe.event(AuthAuditKinds.AccountRolesUpdated, Some(userA))
            _ <- users.updateRoles(userA, TenantId.default, Set.empty, Set(RoleId("editor")), take)
            nTake <- stored(take)
            refused <- users.updateRoles(userB, TenantId.default, Set(RoleId("editor")), Set.empty, grant).either
            rolesB <- users.findRolesByUserAndTenant(userB, TenantId.default)
          yield assertTrue(nGrant == 1L, nRepeat == 0L, nTake == 1L, refused.isLeft, rolesB.isEmpty)
        },
        test("account.password_*: a password and its event are written together; a stale history writes neither; a refused event writes no password") {
          for
            xa <- ZIO.service[TransactorZIO]
            passwords = PostgresPasswordRepository(xa)
            user = userId
            e <- AuditProbe.event(AuthAuditKinds.AccountPasswordSet, Some(user))
            _ <- passwords.create(user, Secret(Array.fill(16)(1.toByte)), Salt(Array.fill(16)(1.toByte)), 5, 3, None, e)
            n <- stored(e)
            // the history has moved on since this check: refused, and the event is not written
            stale <- AuditProbe.event(AuthAuditKinds.AccountPasswordSet, Some(user))
            staleResult <- passwords.create(user, Secret(Array.fill(16)(2.toByte)), Salt(Array.fill(16)(2.toByte)), 5, 3, None, stale).either
            nStale <- stored(stale)
            userB = userId
            refused <- passwords.create(userB, Secret(Array.fill(16)(3.toByte)), Salt(Array.fill(16)(3.toByte)), 5, 3, None, e).either
            listedB <- passwords.list(userB)
            temp <- AuditProbe.event(AuthAuditKinds.AccountPasswordReset, Some(user))
            _ <- passwords.createTemporary(user, Secret(Array.fill(16)(4.toByte)), Salt(Array.fill(16)(4.toByte)), Instant.EPOCH.plusSeconds(3600), temp)
            nTemp <- stored(temp)
            refusedTemp <- passwords.createTemporary(userB, Secret(Array.fill(16)(5.toByte)), Salt(Array.fill(16)(5.toByte)), Instant.EPOCH.plusSeconds(3600), temp).either
            listedTemp <- passwords.list(userB)
          yield assertTrue(
            n == 1L,
            staleResult.isLeft,
            nStale == 0L,
            refused.isLeft,
            listedB.isEmpty,
            nTemp == 1L,
            refusedTemp.isLeft,
            listedTemp.isEmpty,
          )
        },
        test("consent.granted and consent.revoked: written with the change; revoking what was not granted writes none; a refused event keeps the old state") {
          for
            xa <- ZIO.service[TransactorZIO]
            users = PostgresUserRepository(xa)
            consents = PostgresConsentRepository(xa)
            (userA, userB) = (userId, userId)
            _ <- ZIO.foreachDiscard(List(userA, userB))(u =>
              users.register(u, Left(versola.util.Email(s"${newId}@example.com")), TenantId.default, Set.empty, AuditProbe.sample()),
            )
            granted <- AuditProbe.event(AuthAuditKinds.ConsentGranted, Some(userA))
            _ <- consents.upsert(ConsentRecord(userA, app, Set(ScopeToken("openid")), Instant.EPOCH, None), granted)
            nGranted <- stored(granted)
            refusedGrant <- consents.upsert(ConsentRecord(userB, app, Set(ScopeToken("openid")), Instant.EPOCH, None), granted).either
            bConsent <- consents.find(userB, app)
            revoked <- AuditProbe.event(AuthAuditKinds.ConsentRevoked, Some(userA))
            _ <- consents.delete(userA, app, revoked)
            nRevoked <- stored(revoked)
            nothing <- AuditProbe.event(AuthAuditKinds.ConsentRevoked, Some(userA))
            _ <- consents.delete(userA, app, nothing)
            nNothing <- stored(nothing)
          yield assertTrue(nGranted == 1L, refusedGrant.isLeft, bConsent.isEmpty, nRevoked == 1L, nNothing == 0L)
        },
        test("account.passkey_enrolled and account.passkey_removed: written with the change; removing what is not there writes none; a refused event keeps the old state") {
          def passkey(user: UserId) = PasskeyRecord(
            id = CredentialId(Array.fill(16)(Random.nextInt(256).toByte)),
            userId = user,
            publicKey = Array.fill(32)(0.toByte),
            signatureCounter = 0L,
            deviceType = CredentialDeviceType.MultiDevice,
            backedUp = true,
            backupEligible = true,
            transports = List(AuthenticatorTransport.Internal),
            attestationObject = None,
            clientDataJson = None,
            aaguid = None,
            name = None,
            lastUsedAt = None,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
          )
          for
            xa <- ZIO.service[TransactorZIO]
            passkeys = PostgresPasskeyRepository(xa)
            user = userId
            record = passkey(user)
            enrolled <- AuditProbe.event(AuthAuditKinds.AccountPasskeyEnrolled, Some(user))
            _ <- passkeys.insert(record, enrolled)
            nEnrolled <- stored(enrolled)
            other = passkey(user)
            refused <- passkeys.insert(other, enrolled).either
            otherFound <- passkeys.findByCredentialIdAndUser(other.id, user)
            removed <- AuditProbe.event(AuthAuditKinds.AccountPasskeyRemoved, Some(user))
            _ <- passkeys.deleteByUser(record.id, user, removed)
            nRemoved <- stored(removed)
            nothing <- AuditProbe.event(AuthAuditKinds.AccountPasskeyRemoved, Some(user))
            _ <- passkeys.deleteByUser(record.id, user, nothing)
            nNothing <- stored(nothing)
          yield assertTrue(nEnrolled == 1L, refused.isLeft, otherFound.isEmpty, nRemoved == 1L, nNothing == 0L)
        },
      ),
      suite("antifraud administration")(
        test("antifraud.list_changed: an entry's creation, update and removal are each written with their event, which says which; a refused event keeps the old list") {
          val lists = AntifraudListRepositorySpec
          for
            xa <- ZIO.service[TransactorZIO]
            repository = versola.antifraud.PostgresAntifraudListRepository(xa)
            value = newId.toString
            created <- AuditProbe.event(AuthAuditKinds.AntifraudListChanged)
            entry <- repository.upsert(lists.entry(value = value), "alice", lists.at, created)
            action1 <- AuditProbe.detail(created, "action")
            id1 <- AuditProbe.detail(created, "entryId")
            updated <- AuditProbe.event(AuthAuditKinds.AntifraudListChanged)
            _ <- repository.upsert(lists.entry(value = value), "alice", lists.at, updated)
            action2 <- AuditProbe.detail(updated, "action")
            removed <- AuditProbe.event(AuthAuditKinds.AntifraudListChanged)
            gone <- repository.delete(entry.id, "alice", lists.at, removed)
            action3 <- AuditProbe.detail(removed, "action")
            again <- AuditProbe.event(AuthAuditKinds.AntifraudListChanged)
            goneAgain <- repository.delete(entry.id, "alice", lists.at, again)
            nAgain <- stored(again)
            other = lists.entry(value = newId.toString)
            refused <- repository.upsert(other, "alice", lists.at, created).either
            otherFound <- repository.find(other.id)
          yield assertTrue(
            action1.contains(Json.Str("create")),
            id1.contains(Json.Str(entry.id.toString)),
            action2.contains(Json.Str("update")),
            gone,
            action3.contains(Json.Str("delete")),
            !goneAgain,
            nAgain == 0L,
            refused.isLeft,
            otherFound.isEmpty,
          )
        },
        test("antifraud.settings_changed: the change and its event are one transaction, which says the version; a change that was not made (a stale version) writes none") {
          val settings = AntifraudSettingsRepositorySpec
          for
            xa <- ZIO.service[TransactorZIO]
            repository = versola.antifraud.PostgresAntifraudSettingsRepository(xa)
            e <- AuditProbe.event(AuthAuditKinds.AntifraudSettingsChanged)
            written <- repository.put(settings.tuned, "alice", settings.at, None, e)
            version <- AuditProbe.detail(e, "version")
            stale <- AuditProbe.event(AuthAuditKinds.AntifraudSettingsChanged)
            notWritten <- repository.put(Tuning.off, "bob", settings.at, written.map(_ + 100), stale)
            nStale <- stored(stale)
            refused <- repository.put(Tuning.off, "bob", settings.at, None, e).either
            stored <- repository.find
          yield assertTrue(
            written.isDefined,
            version == written.map(v => Json.Num(v)),
            notWritten.isEmpty,
            nStale == 0L,
            refused.isLeft,
            stored.map(_.version) == written,
          )
        },
        test("antifraud.counters_reset: the reset and its event are one transaction, which says the generation; a refused event records no reset") {
          val resets = AntifraudCounterResetRepositorySpec
          for
            xa <- ZIO.service[TransactorZIO]
            repository = versola.antifraud.PostgresAntifraudCounterResetRepository(xa)
            e <- AuditProbe.event(AuthAuditKinds.AntifraudCountersReset)
            generation <- repository.record(resets.whole, "alice", resets.at, e)
            said <- AuditProbe.detail(e, "generation")
            before <- repository.latest
            refused <- repository.record(resets.whole, "bob", resets.at, e).either
            after <- repository.latest
          yield assertTrue(said.contains(Json.Num(generation)), refused.isLeft, before == after)
        },
      ),
    ) @@ TestAspect.sequential
