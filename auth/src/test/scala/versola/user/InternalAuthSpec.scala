package versola.user

import versola.audit.AuthActors
import versola.auth.TestEnvConfig
import versola.user.model.UserId
import versola.util.audit.AuditContext
import versola.util.http.Observability
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.logging.logContext
import zio.test.*
import zio.test.TestAspect.silentLogging

import javax.crypto.spec.SecretKeySpec

object InternalAuthSpec extends ZIOSpecDefault:

  private val secretKey = SecretKeySpec(Array.fill(32)(7.toByte), "AES")
  private val configLayer = ZLayer.succeed(
    TestEnvConfig.coreConfig.copy(central = CoreConfig.CentralSyncConfig(zio.http.URL.empty, secretKey)),
  )

  private def makeToken(aud: List[String]): zio.Task[String] =
    JWT.serialize(
      JWT.Claims("auth", "auth", aud, Json.Obj()),
      10.minutes,
      JWT.Signature.Symmetric(secretKey),
    )

  def spec = suite("InternalAuth")(
    suite("InternalAuthClaims JsonCodec")(
      test("decodes aud as single string") {
        val json = """{"iss":"auth","sub":"auth","aud":"central"}"""
        val result = json.fromJson[InternalAuthClaims]
        assertTrue(result == Right(InternalAuthClaims("auth", "auth", List("central"))))
      },
      test("decodes aud as array of strings") {
        val json = """{"iss":"auth","sub":"auth","aud":["central","edge"]}"""
        val result = json.fromJson[InternalAuthClaims]
        assertTrue(result == Right(InternalAuthClaims("auth", "auth", List("central", "edge"))))
      },
      test("encodes single-element aud as string") {
        val claims = InternalAuthClaims("auth", "auth", List("central"))
        val json = claims.toJson.fromJson[Json].toOption.get
        val audNode = json.asObject.flatMap(_.get("aud"))
        assertTrue(audNode.exists(_.isInstanceOf[Json.Str]))
      },
      test("encodes multi-element aud as array") {
        val claims = InternalAuthClaims("auth", "auth", List("central", "edge"))
        val json = claims.toJson.fromJson[Json].toOption.get
        val audNode = json.asObject.flatMap(_.get("aud"))
        assertTrue(audNode.exists(_.isInstanceOf[Json.Arr]))
      },
      test("fails when aud is neither string nor array") {
        val json = """{"iss":"auth","sub":"auth","aud":42}"""
        val result = json.fromJson[InternalAuthClaims]
        assertTrue(result.isLeft)
      },
      test("fails when aud array contains non-strings") {
        val json = """{"iss":"auth","sub":"auth","aud":[1,2]}"""
        val result = json.fromJson[InternalAuthClaims]
        assertTrue(result.isLeft)
      },
    ),
    suite("authorizeInternal")(
      test("succeeds with a valid Bearer token") {
        for
          token <- makeToken(List("central"))
          request = zio.http.Request.get(zio.http.URL.empty)
            .addHeader(zio.http.Header.Authorization.Bearer(token))
          result <- authorizeInternal(request).exit
        yield assertTrue(result.isSuccess)
      }.provide(configLayer),
      test("a call is the central service's, until it names an administrator with actedBy") {
        val admin = UserId(java.util.UUID.fromString("0192b3c0-0000-7000-8000-0000000000aa"))
        for
          token <- makeToken(List("central"))
          authenticated = zio.http.Request.get(zio.http.URL.empty).addHeader(zio.http.Header.Authorization.Bearer(token))
          anonymous <- AuditContext.reset(AuditContext.State.empty)(authorizeInternal(authenticated) *> actedBy(None) *> AuditContext.current)
          named <- AuditContext.reset(AuditContext.State.empty)(authorizeInternal(authenticated) *> actedBy(Some(admin)) *> AuditContext.current)
        yield assertTrue(
          anonymous.actor == Some((AuthActors.service, Some("central"))),
          named.actor == Some((AuthActors.admin, Some(admin.toString))),
        )
      }.provide(configLayer),
      test("the address of the administrator's call is the ip of the request's events, when it is an address and the caller is central") {
        def ipAfter(request: zio.http.Request) =
          logContext.locallyWith(identity)(authorizeInternal(request) *> Observability.currentIp)
        for
          token <- makeToken(List("central"))
          authenticated = zio.http.Request.get(zio.http.URL.empty).addHeader(zio.http.Header.Authorization.Bearer(token))
          v4 <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "203.0.113.7")))
          v6 <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "2001:db8:1:2::1")))
          none <- ipAfter(authenticated)
          text <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "not-an-address")))
          listed <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "203.0.113.7, 198.51.100.1")))
          wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
          forged <- JWT.serialize(JWT.Claims("auth", "auth", List("central"), Json.Obj()), 10.minutes, JWT.Signature.Symmetric(wrongKey))
          refused <- logContext.locallyWith(identity)(
            authorizeInternal(
              zio.http.Request.get(zio.http.URL.empty).addHeader(zio.http.Header.Authorization.Bearer(forged))
                .updateURL(_.addQueryParam(AdminIpParam, "203.0.113.7")),
            ).exit *> Observability.currentIp,
          )
        yield assertTrue(
          v4.contains("203.0.113.7"),
          v6.contains("2001:db8:1:2::1"),
          none.isEmpty,
          text.isEmpty,
          listed.isEmpty,
          // a caller that is not central does not get to say where a call came from
          refused.isEmpty,
        )
      }.provide(configLayer),
      test("the user agent an event stores is the short form of the header, never the raw header") {
        val raw =
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15 " + ("x" * 4000)
        for
          token <- makeToken(List("central"))
          authenticated = zio.http.Request.get(zio.http.URL.empty).addHeader(zio.http.Header.Authorization.Bearer(token))
          browser <-
            AuditContext.reset(AuditContext.State.empty)(authorizeInternal(authenticated.addHeader("User-Agent", raw)) *> AuditContext.current)
          unnamed <-
            AuditContext.reset(AuditContext.State.empty)(authorizeInternal(authenticated.addHeader("User-Agent", "curl/8")) *> AuditContext.current)
          none <- AuditContext.reset(AuditContext.State.empty)(authorizeInternal(authenticated) *> AuditContext.current)
        yield assertTrue(
          browser.userAgent.exists(ua => ua.length < 64 && !ua.contains("xxxx")),
          unnamed.userAgent.nonEmpty && unnamed.userAgent.forall(_.length < 64),
          none.userAgent.isEmpty,
        )
      }.provide(configLayer),
      test("a call whose token does not verify names no actor") {
        val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
        for
          token <- JWT.serialize(JWT.Claims("auth", "auth", List("central"), Json.Obj()), 10.minutes, JWT.Signature.Symmetric(wrongKey))
          request = zio.http.Request.get(zio.http.URL.empty).addHeader(zio.http.Header.Authorization.Bearer(token))
          result <- AuditContext.reset(AuditContext.State.empty)(authorizeInternal(request).exit.zip(AuditContext.current))
          (exit, context) = result
        yield assertTrue(exit.isFailure, context.actor.isEmpty)
      }.provide(configLayer),
      test("fails when Authorization header is missing") {
        val request = zio.http.Request.get(zio.http.URL.empty)
        for
          result <- authorizeInternal(request).exit
        yield assertTrue(result.isFailure)
      }.provide(configLayer),
      test("fails when token is signed with wrong key") {
        val wrongKey = SecretKeySpec(Array.fill(32)(99.toByte), "AES")
        for
          token <- JWT.serialize(
            JWT.Claims("auth", "auth", List("central"), Json.Obj()),
            10.minutes,
            JWT.Signature.Symmetric(wrongKey),
          )
          request = zio.http.Request.get(zio.http.URL.empty)
            .addHeader(zio.http.Header.Authorization.Bearer(token))
          result <- authorizeInternal(request).exit
        yield assertTrue(result.isFailure)
      }.provide(configLayer),
    ),
  ) @@ silentLogging
