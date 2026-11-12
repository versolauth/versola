package versola.e2e.flows.audit

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** What auth writes to its audit log (#469) for a user's whole session, read back the way central's per-user report reads it: through auth's
  * internal `GET /users/audit-events` and `/users/audit-summary`, with the service token the two share.
  *
  * A sign-in with a password, the tokens it issues (the code exchange and a refresh), the revocation of the refresh token and the
  * RP-initiated logout are performed over HTTP against the staged stack, and the rows that come back are the ones the writer stored in
  * Postgres, in order. The writer batches and rests for up to a second, so the spec waits for the rows to arrive, bounded, and never sleeps.
  */
object AuditLogFlowSpec extends E2ESpec:

  private val offlineScope = "openid offline_access"

  private case class Session(tokens: TokenResult.Success, idToken: String, refreshToken: String)

  /** In non-prod the one-time code is always the first digits of "1234567890". */
  private val fixedOtp = "123456"

  private def login(s: Flows.Setup, auth: OAuthClient): Task[Session] =
    for
      authorize <- auth.authorize(scope = offlineScope, clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, s.email.get, credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).flatMap(_.assertRedirect)
      tokens <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      idToken <- ZIO.fromOption(tokens.idToken).orElseFail(RuntimeException("expected an id_token for the openid scope"))
      refresh <- ZIO.fromOption(tokens.refreshToken).orElseFail(RuntimeException("expected a refresh token for offline_access"))
    yield Session(tokens, idToken, refresh)

  /** What a row says of itself, as the endpoint serves it. */
  private case class Row(
      kind: String,
      outcome: String,
      reason: Option[String],
      at: Instant,
      details: Json.Obj,
      clientId: Option[String],
      ip: Option[String],
  )

  private def rowOf(json: Json): Option[Row] =
    for
      obj <- json.asObject
      kind <- obj.get("kind").flatMap(_.asString)
      outcome <- obj.get("outcome").flatMap(_.asString)
      at <- obj.get("at").flatMap(_.asString).map(Instant.parse)
    yield Row(
      kind,
      outcome,
      obj.get("reason").flatMap(_.asString),
      at,
      obj.get("details").flatMap(_.asObject).getOrElse(Json.Obj()),
      obj.get("clientId").flatMap(_.asString),
      obj.get("ip").flatMap(_.asString),
    )

  private case class Page(rows: List[Row], nextCursor: Option[String], retentionFrom: Instant)

  private def page(auth: OAuthClient, userId: String, query: String): Task[Page] =
    for
      response <- auth.auditGet(s"/users/audit-events?id=$userId$query")
      body <- response.body.asString
      _ <- ZIO.fail(RuntimeException(s"events answered ${response.status}: $body")).unless(response.status == Status.Ok)
      json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield Page(
      json.get("events").flatMap(_.asArray).map(_.toList.flatMap(rowOf)).getOrElse(Nil),
      json.get("nextCursor").flatMap(_.asString),
      json.get("retentionFrom").flatMap(_.asString).map(Instant.parse).getOrElse(Instant.EPOCH),
    )

  /** Every row of the user from `from` on, newest first, waiting (bounded) until `done` holds: the rows reach the table when the writer's
    * batch is written, up to a second after the request that made them. */
  private def awaitRows(auth: OAuthClient, userId: String, from: Instant)(done: List[Row] => Boolean): Task[List[Row]] =
    def attempt(left: Int): Task[List[Row]] =
      page(auth, userId, s"&from=$from&limit=500").map(_.rows).flatMap: rows =>
        if done(rows) || left == 0 then ZIO.succeed(rows) else ZIO.sleep(250.millis) *> attempt(left - 1)
    attempt(80)

  private def kinds(rows: List[Row]): List[String] = rows.map(_.kind)

  /** The rows of a whole case that signs in and logs out: waited for together, not the logout alone. The writer batches, and a wait that is
    * satisfied by the last row alone returns while the sign-in's rows are still in the batch before it, or satisfied by a row of the previous
    * case, which shares the user, before this case's own rows exist at all. */
  private def signedInAndOut(rows: List[Row]): Boolean =
    List("login.completed", "token.issued", "session.ended").forall(kinds(rows).contains)

  def spec = suite("Audit log")(
    test("a sign-in, its tokens, a revocation and a logout are stored in order and served per user") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        // the stack runs on this machine, so the clocks agree; the user is shared by the cases of this spec, so only what follows this instant is theirs.
        // Not backdated: a margin would reach back into the previous case, whose last rows (its logout) would then be in this case's window
        started = Instant.now
        userId = s.userId.toString
        session <- login(s, auth)
        // RFC 6749 §6: the refresh grant (not an event unless the tenant turned on `auditSettings.tokenRefresh`, which it has not), then RFC 7009: the refresh token revoked, then OIDC RP-Initiated Logout
        refreshed <- auth.refresh(session.refreshToken, Some(s.clientId), Some(s.clientSecret)).success
        refreshedRefresh <- ZIO.fromOption(refreshed.refreshToken).orElseFail(RuntimeException("expected the rotated refresh token"))
        revoked <- auth.revoke(refreshedRefresh, s.clientId, s.clientSecret, tokenTypeHint = Some("refresh_token"))
        logout <- auth.logoutWithIdTokenHint(session.idToken)
        rows <- awaitRows(auth, userId, started)(r => signedInAndOut(r) && kinds(r).contains("token.revoked"))
        // oldest first, the order things happened in
        story = rows.reverse
      yield assertTrue(
        revoked.status == Status.Ok,
        story.map(r => (r.kind, r.outcome)).filter((k, _) => k != "login.step") == List(
          ("login.completed", "success"),
          ("token.issued", "success"),
          ("token.revoked", "success"),
          ("session.ended", "success"),
        ),
        // the code step is there, a success, before the session was made
        story.headOption.map(r => (r.kind, r.outcome, r.details.get("step").flatMap(_.asString))).contains(("login.step", "success", Some("otp"))),
        story.map(_.at) == story.map(_.at).sorted,
        // newest first: what the endpoint serves is the reverse of the story
        rows.map(_.at) == rows.map(_.at).sorted.reverse,
        story.filter(_.kind == "token.issued").flatMap(r => r.details.get("grant").flatMap(_.asString)) == List("authorization_code"),
        story.filter(_.kind == "token.issued").flatMap(_.clientId).toSet == Set(s.clientId),
        story.find(_.kind == "token.revoked").flatMap(r => r.details.get("cause").flatMap(_.asString)).contains("rfc7009"),
        story.find(_.kind == "session.ended").flatMap(r => r.details.get("cause").flatMap(_.asString)).contains("rp_logout"),
        // no token, secret or password is in anything that was stored
        !rows.exists(r =>
          List(session.tokens.accessToken, session.refreshToken, refreshedRefresh, fixedOtp, s.clientSecret).exists(r.details.toJson.contains(_)),
        ),
      )
    },
    test("the summary counts the same session: by kind and client, with the address and user agent, sessions and the factors") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        started = Instant.now
        userId = s.userId.toString
        session <- login(s, auth)
        _ <- auth.logoutWithIdTokenHint(session.idToken)
        _ <- awaitRows(auth, userId, started)(signedInAndOut)
        response <- auth.auditGet(s"/users/audit-summary?id=$userId&from=$started")
        body <- response.body.asString
        json <- ZIO.fromEither(body.fromJson[Json.Obj]).mapError(RuntimeException(_))
        byKind = json.get("byKind").flatMap(_.asArray).map(_.toList.flatMap(_.asObject)).getOrElse(Nil)
        count = (kind: String) =>
          byKind.filter(_.get("kind").flatMap(_.asString).contains(kind)).flatMap(_.get("count").flatMap(_.asNumber).map(_.value.longValue)).sum
        sessions = json.get("sessions").flatMap(_.asObject)
      yield assertTrue(
        response.status == Status.Ok,
        json.keys.toSet == Set("window", "byKind", "byClient", "ips", "userAgents", "sessions", "countries", "factors", "high"),
        count("login.completed") == 1L,
        count("token.issued") == 1L,
        count("session.ended") == 1L,
        sessions.flatMap(_.get("created")).flatMap(_.asNumber).map(_.value.longValue).contains(1L),
        sessions.flatMap(_.get("ended")).flatMap(_.asNumber).map(_.value.longValue).contains(1L),
        json.get(
          "byClient",
        ).flatMap(_.asArray).map(_.toList.flatMap(_.asObject).flatMap(_.get("clientId")).flatMap(_.asString)).exists(_.contains(s.clientId)),
        // the address the requests came from, from the socket (the dev config reads no header)
        json.get("ips").flatMap(_.asArray).exists(_.nonEmpty),
      )
    },
    test("pages by cursor without repeating or missing a row") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        started = Instant.now
        userId = s.userId.toString
        session <- login(s, auth)
        _ <- auth.logoutWithIdTokenHint(session.idToken)
        all <- awaitRows(auth, userId, started)(signedInAndOut)
        first <- page(auth, userId, s"&from=$started&limit=2")
        second <- page(auth, userId, s"&from=$started&limit=2&cursor=${first.nextCursor.getOrElse("")}")
        rest <- ZIO.iterate((second.nextCursor, List.empty[Row]))(_._1.isDefined): (cursor, acc) =>
          page(auth, userId, s"&from=$started&limit=2&cursor=${cursor.get}").map(p => (p.nextCursor, acc ++ p.rows))
      yield assertTrue(
        first.rows.size == 2,
        first.nextCursor.isDefined,
        (first.rows ++ second.rows ++ rest._2).map(r => (r.kind, r.at)) == all.map(r => (r.kind, r.at)),
      )
    },
    test("is for the service token alone, and refuses a window outside the retained one") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        userId = s.userId.toString
        noToken <- auth.auditGet(s"/users/audit-events?id=$userId", OAuthClient.Authenticated.No)
        wrongKey <- auth.auditGet(s"/users/audit-events?id=$userId", OAuthClient.Authenticated.WrongKey)
        tooEarly <- auth.auditGet(s"/users/audit-events?id=$userId&from=${Instant.now.minusSeconds(8 * 86400)}")
        reversed <- auth.auditGet(s"/users/audit-events?id=$userId&from=${Instant.now}&to=${Instant.now.minusSeconds(60)}")
        notAUser <- auth.auditGet("/users/audit-events?id=not-a-uuid")
        badCursor <- auth.auditGet(s"/users/audit-events?id=$userId&cursor=garbage")
        badLimit <- auth.auditGet(s"/users/audit-events?id=$userId&limit=501")
        noUser <- auth.auditGet("/users/audit-events")
        summaryNoToken <- auth.auditGet(s"/users/audit-summary?id=$userId", OAuthClient.Authenticated.No)
        unknownUser <- auth.auditGet(s"/users/audit-events?id=${java.util.UUID.randomUUID()}")
        unknownBody <- unknownUser.body.asString
      yield assertTrue(
        noToken.status == Status.Unauthorized,
        wrongKey.status == Status.Unauthorized,
        summaryNoToken.status == Status.Unauthorized,
        tooEarly.status == Status.BadRequest,
        reversed.status == Status.BadRequest,
        notAUser.status == Status.BadRequest,
        badCursor.status == Status.BadRequest,
        badLimit.status == Status.BadRequest,
        noUser.status == Status.BadRequest,
        // a user with no events is an empty page, not an error and not a 404
        unknownUser.status == Status.Ok,
        unknownBody.contains("\"events\":[]"),
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
