package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** The per-user report with auth's part in it (#469): a sign-in, a token and a revocation of a real user, and what an administrator
  * did to them, in one list, newest first, from central's `GET /users/audit?id=`.
  *
  * Needs an auth that serves `GET /users/audit-events?id=` and `/users/audit-summary?id=` (stream B of the audit log): without them the report is
  * `partial: ["auth"]`, which `AdminAuditSpec` asserts.
  */
object AdminAuditReportWithAuthSpec extends E2ESpec:

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] = Chunk(TestAspect.withLiveClock)

  private val fixedOtp = "123456"

  private val callerIp = "203.0.113.7"

  def spec = suite("Admin audit: the report with auth's part")(
    test("a user's sign-in and token are in the report with what an administrator did to them, newest first, and it is not partial") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        admin = UUID.randomUUID().toString
        // what edge adds to a call: the administrator and the address the call came from
        central = auth.central.withQuery("actorId", admin).withQuery("actorIp", callerIp)
        email <- CentralApi.email("audit-auth")
        userId <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()
        authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, email, credential.csrf)
        otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
        code <- submit.assertRedirect
        _ <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        _ <- central.delete("/users/sessions", "userId" -> userId.toString)
        // the writer of each service rests up to a second between batches
        result <- central.get("/users/audit", "id" -> userId.toString)
          .repeat(Schedule.spaced(500.millis) *> Schedule.recurUntil[ApiResult](r =>
            r.body.contains("token.issued") && r.body.contains("admin.user-session.delete") && r.body.contains("session.ended"),
          ))
          .timeout(30.seconds)
          .someOrElseZIO(central.get("/users/audit", "id" -> userId.toString))
        body <- result.obj
        events = body.objs("events")
        kinds = events.flatMap(_.str("kind")).toSet
        times = events.flatMap(_.str("at"))
        ipOf = (kind: String) => events.filter(_.str("kind").contains(kind)).flatMap(_.str("ip")).toSet
        traceOf = (kind: String) => events.filter(_.str("kind").contains(kind)).flatMap(_.str("traceId")).toSet
      yield assertTrue(
        result.status == Status.Ok,
        body.get("partial").contains(Json.Arr()),
        kinds.contains("login.completed"),
        kinds.contains("token.issued"),
        kinds.contains("admin.user-session.delete"),
        events.filter(_.str("source").contains("auth")).nonEmpty,
        events.filter(_.str("source").contains("central")).nonEmpty,
        times == times.sorted(using Ordering.String.reverse),
        // central's row of the revocation and auth's row of its effect are one trace: the request's trace context reaches auth
        traceOf("admin.user-session.delete").nonEmpty,
        traceOf("admin.user-session.delete") == traceOf("session.ended"),
        // from where: the address of the administrator's call is the ip of central's row and of auth's row of its effect
        ipOf("admin.user-session.delete") == Set(callerIp),
        ipOf("session.ended") == Set(callerIp),
        body.obj("summary").exists(_.objs("adminActions").exists(_.str("kind").contains("admin.user-session.delete"))),
      )
    },
  )
