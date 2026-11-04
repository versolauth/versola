package versola.user

import versola.audit.AuthActors
import versola.auth.TestEnvConfig
import versola.user.model.UserId
import versola.util.audit.AuditContext
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.json.*
import zio.json.ast.Json
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
