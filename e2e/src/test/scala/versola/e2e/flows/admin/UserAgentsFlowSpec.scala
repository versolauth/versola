package versola.e2e.flows.admin

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** What an administrator sees of a user's devices through central: each user agent auth stored,
  * the session running under it with its expiry, and -- once the session ended -- the user agent
  * on its own, named, until it is forgotten. Each test signs a user of its own in on user agents
  * of its own; the sessions it ends are not shared with any other spec.
  */
object UserAgentsFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  private val chromeOnMac =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
  private val safariOnIphone =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

  private def freshUser(auth: OAuthClient): Task[(String, String)] =
    for
      email <- CentralApi.email("user-agents")
      userId <- auth.registerUser(email = Some(email))
      _ <- auth.flushUserOutbox()
    yield (userId.toString, email)

  /** Signs the user in on a user agent that sends `userAgent` as its header and holds no cookies,
    * so auth sees a device it has not met. Returns the SSO session cookie. */
  private def login(s: Flows.Setup, auth: OAuthClient, email: String, userAgent: String): Task[String] =
    for
      authorize <- auth.authorize(
        clientId = Some(s.clientId),
        redirectUri = Some(s.redirectUri),
        userAgent = Some(userAgent),
      ).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, email, credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
      _ <- submit.assertRedirect
      sessionCookie <- ZIO.fromOption(submit.sessionCookie).orElseFail(RuntimeException("No SSO_SESSION cookie after the OTP submission"))
    yield sessionCookie

  private def silentlyAuthorizes(s: Flows.Setup, auth: OAuthClient, sessionCookie: String): Task[Boolean] =
    auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, scope = Some("openid"), prompt = Some("none"), sessionCookie = Some(sessionCookie))
      .map(_.location.contains("code="))

  private def sessionsOf(auth: OAuthClient, userId: String): Task[Chunk[Json.Obj]] =
    auth.central.get("/users/sessions", "id" -> userId).flatMap(_.objects)

  private def devicesOf(auth: OAuthClient, userId: String): Task[Chunk[Json.Obj]] =
    auth.central.get("/users/user-agents", "id" -> userId).flatMap(_.objects)

  private def instant(obj: Option[Json.Obj], field: String): Option[Instant] =
    obj.flatMap(_.str(field)).flatMap(text => scala.util.Try(Instant.parse(text)).toOption)

  private def deviceOf(devices: Chunk[Json.Obj], browser: String): Option[Json.Obj] =
    devices.find(_.obj("userAgent").flatMap(_.str("browser")).contains(browser))

  def spec = suite("A user's devices through central")(
    test("lists each user agent with its session, expiries and the user agent record auth stored") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (userId, email) <- freshUser(auth)
        before <- Clock.ClockLive.instant
        _ <- login(s, auth, email, chromeOnMac)
        _ <- login(s, auth, email, safariOnIphone)
        after <- Clock.ClockLive.instant
        devices <- devicesOf(auth, userId)
        sessions <- sessionsOf(auth, userId)
        chrome = deviceOf(devices, "Chrome")
        safari = deviceOf(devices, "Safari")
        chromeSession = chrome.flatMap(_.obj("session"))
        chromeAgent = chrome.flatMap(_.obj("userAgent"))
        sessionExpiry = instant(chromeSession, "expiresAt")
        agentExpiry = instant(chromeAgent, "expiresAt")
        agentCreated = instant(chromeAgent, "createdAt")
        idle = instant(chromeSession, "idleExpiresAt")
      yield assertTrue(devices.size == 2, sessions.size == 2) &&
        assertTrue(chrome.isDefined, safari.isDefined).label("both user agents are named from the header they sent") &&
        assertTrue(chromeAgent.flatMap(_.str("userAgent")).contains(chromeOnMac)).label("the raw header is stored and returned") &&
        assertTrue(
          chromeAgent.flatMap(_.str("platform")).contains("desktop"),
          chromeAgent.flatMap(_.str("os")).exists(_.startsWith("macOS")),
          chromeAgent.flatMap(_.str("version")).contains("130"),
          deviceOf(devices, "Safari").flatMap(_.obj("userAgent")).flatMap(_.str("platform")).contains("ios"),
        ) &&
        assertTrue(chromeSession.flatMap(_.str("publicId")).isDefined, chromeSession.map(_.objs("clients").size).contains(1)) &&
        assertTrue(sessionExpiry.exists(_.isAfter(after))).label("the session expires in the future") &&
        assertTrue(agentExpiry.exists(_.isAfter(after))).label("the device is remembered until a future instant") &&
        assertTrue(agentCreated.exists(created => !created.isBefore(before.minusSeconds(60)) && !created.isAfter(after.plusSeconds(60)))) &&
        assertTrue(idle.forall(at => !at.isAfter(sessionExpiry.get))).label("an idle expiry, when there is one, is not past the absolute expiry") &&
        assertTrue(chrome.exists(_.arr("endedSessionIds").isEmpty)) &&
        assertTrue(
          sessions.forall(session => session.str("expiresAt").isDefined && session.obj("userAgent").flatMap(_.str("id")).isDefined),
        ).label("the sessions listing carries the new fields next to the old ones") &&
        assertTrue(sessions.forall(session => session.str("publicId").isDefined && session.has("clients") && session.str("createdAt").isDefined))
    },
    test("a device whose session ended stays listed by name with session null until it is forgotten") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (userId, email) <- freshUser(auth)
        chromeCookie <- login(s, auth, email, chromeOnMac)
        safariCookie <- login(s, auth, email, safariOnIphone)
        before <- devicesOf(auth, userId)
        target <- ZIO.fromOption(deviceOf(before, "Chrome").flatMap(_.obj("session")).flatMap(_.str("publicId")))
          .orElseFail(RuntimeException(s"No Chrome session listed: $before"))
        agentId <- ZIO.fromOption(deviceOf(before, "Chrome").flatMap(_.obj("userAgent")).flatMap(_.str("id")))
          .orElseFail(RuntimeException(s"No Chrome user agent listed: $before"))
        ended <- auth.central.delete("/users/sessions", "id" -> userId, "sessionId" -> target)
        after <- devicesOf(auth, userId)
        sessions <- sessionsOf(auth, userId)
        chrome = deviceOf(after, "Chrome")
        safari = deviceOf(after, "Safari")
        chromeAlive <- silentlyAuthorizes(s, auth, chromeCookie)
        safariAlive <- silentlyAuthorizes(s, auth, safariCookie)
      yield assertTrue(ended.status == Status.NoContent, after.size == 2) &&
        assertTrue(chrome.exists(_.isNull("session"))).label("a signed-out device carries session: null, not an absent member") &&
        assertTrue(chrome.flatMap(_.obj("userAgent")).flatMap(_.str("id")).contains(agentId)).label("it is the same user agent, still remembered") &&
        assertTrue(chrome.exists(_.strings("endedSessionIds").contains(target))).label("the ended session is named, so a refresh token can be tied to the device") &&
        assertTrue(chrome.flatMap(_.obj("userAgent")).flatMap(_.str("userAgent")).contains(chromeOnMac)) &&
        assertTrue(safari.exists(_.obj("session").isDefined)).label("the other device is untouched") &&
        assertTrue(sessions.size == 1).label("the sessions listing keeps listing live sessions only") &&
        assertTrue(!chromeAlive, safariAlive)
    },
    test("forgetting a device removes its user agent and session, and only the owner can") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        (userId, email) <- freshUser(auth)
        (otherUserId, _) <- freshUser(auth)
        chromeCookie <- login(s, auth, email, chromeOnMac)
        safariCookie <- login(s, auth, email, safariOnIphone)
        before <- devicesOf(auth, userId)
        agentId <- ZIO.fromOption(deviceOf(before, "Chrome").flatMap(_.obj("userAgent")).flatMap(_.str("id")))
          .orElseFail(RuntimeException(s"No Chrome user agent listed: $before"))
        foreign <- auth.central.delete("/users/user-agents", "id" -> otherUserId, "userAgentId" -> agentId)
        stillThere <- devicesOf(auth, userId)
        forgotten <- auth.central.delete("/users/user-agents", "id" -> userId, "userAgentId" -> agentId)
        after <- devicesOf(auth, userId)
        chromeAlive <- silentlyAuthorizes(s, auth, chromeCookie)
        safariAlive <- silentlyAuthorizes(s, auth, safariCookie)
        again <- auth.central.delete("/users/user-agents", "id" -> userId, "userAgentId" -> agentId)
        missing <- auth.central.delete("/users/user-agents", "id" -> userId)
      yield assertTrue(foreign.status == Status.NotFound).label("another user's id is not proof of owning the device") &&
        assertTrue(stillThere.size == 2) &&
        assertTrue(forgotten.status == Status.NoContent) &&
        assertTrue(after.size == 1, deviceOf(after, "Chrome").isEmpty, deviceOf(after, "Safari").exists(_.obj("session").isDefined)) &&
        assertTrue(!chromeAlive, safariAlive).label("forgetting signs that device out and leaves the other one") &&
        assertTrue(again.status == Status.NotFound).label("a device that is already forgotten is no longer the user's") &&
        assertTrue(missing.status == Status.BadRequest)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
