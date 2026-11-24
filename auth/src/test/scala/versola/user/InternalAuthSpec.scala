package versola.user

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.audit.AuthActors
import versola.auth.TestEnvConfig
import versola.user.model.UserId
import versola.util.audit.AuditContext
import versola.util.http.Observability
import versola.util.{JWT, RsaKeyPair}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.logging.logContext
import zio.test.*
import zio.test.TestAspect.silentLogging

object InternalAuthSpec extends ZIOSpecDefault:

  private val configLayer = ZLayer.succeed(TestEnvConfig.coreConfig)

  /** An auth that is the cell `kz-1`. */
  private val cellLayer = ZLayer.succeed(TestEnvConfig.cellCoreConfig)

  private val cell = TestEnvConfig.cellId: String

  /** What central makes for a call to an auth: RS256 under its service key. */
  private def makeToken(
      aud: List[String],
      issuer: String = "central",
      pair: RsaKeyPair = TestEnvConfig.centralKeyPair,
      ttl: Duration = 10.minutes,
  ): zio.Task[String] =
    JWT.serialize(JWT.Claims(issuer, issuer, aud, Json.Obj()), ttl, TestEnvConfig.signedBy(pair))

  private def requestWith(token: String) =
    zio.http.Request.get(zio.http.URL.empty).addHeader(zio.http.Header.Authorization.Bearer(token))

  /** What the call was answered with, and the closed code its error context carries. */
  private def outcome(token: String) =
    logContext.locallyWith(identity)(
      authorizeInternal(requestWith(token)).exit.zip(logContext.get.map(_.get(Observability.error).map(_.code))),
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
          token <- makeToken(List("auth"))
          request = zio.http.Request.get(zio.http.URL.empty)
            .addHeader(zio.http.Header.Authorization.Bearer(token))
          result <- authorizeInternal(request).exit
        yield assertTrue(result.isSuccess)
      }.provide(configLayer),
      test("a call is the central service's, until it names an administrator with actedBy") {
        val admin = UserId(java.util.UUID.fromString("0192b3c0-0000-7000-8000-0000000000aa"))
        for
          token <- makeToken(List("auth"))
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
          token <- makeToken(List("auth"))
          authenticated = zio.http.Request.get(zio.http.URL.empty).addHeader(zio.http.Header.Authorization.Bearer(token))
          v4 <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "203.0.113.7")))
          v6 <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "2001:db8:1:2::1")))
          none <- ipAfter(authenticated)
          text <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "not-an-address")))
          listed <- ipAfter(authenticated.updateURL(_.addQueryParam(AdminIpParam, "203.0.113.7, 198.51.100.1")))
          forged <- makeToken(List("auth"), pair = TestEnvConfig.impostorKeyPair)
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
          token <- makeToken(List("auth"))
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
        for
          token <- makeToken(List("auth"), pair = TestEnvConfig.impostorKeyPair)
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
      test("fails when token is signed with another key under central's kid") {
        for
          token <- makeToken(List("auth"), pair = TestEnvConfig.impostorKeyPair)
          request = zio.http.Request.get(zio.http.URL.empty)
            .addHeader(zio.http.Header.Authorization.Bearer(token))
          result <- authorizeInternal(request).exit
        yield assertTrue(result.isFailure)
      }.provide(configLayer),
    ),
    // versolauth/versola#1050: who the token is for, and who made it.
    suite("whose token it is, and for whom")(
      test("an auth with a cell id accepts a token made for that cell") {
        for
          token <- makeToken(List(cell))
          result <- authorizeInternal(requestWith(token)).exit
        yield assertTrue(result.isSuccess)
      }.provide(cellLayer),
      test("an auth with a cell id accepts a token that names its cell among others") {
        for
          token <- makeToken(List("kz-2", cell))
          result <- authorizeInternal(requestWith(token)).exit
        yield assertTrue(result.isSuccess)
      }.provide(cellLayer),
      test("an auth with a cell id refuses a token made for another cell") {
        for
          token <- makeToken(List("kz-2"))
          result <- outcome(token)
        yield assertTrue(result._1.isFailure, result._2.contains("internal_token_invalid"))
      }.provide(cellLayer),
      test("an auth with a cell id refuses a token made for \"auth\", which is the audience of an auth with none") {
        for
          token <- makeToken(List("auth"))
          result <- outcome(token)
        yield assertTrue(result._1.isFailure, result._2.contains("internal_token_invalid"))
      }.provide(cellLayer),
      test("an auth with no cell id accepts \"auth\" and refuses a cell id") {
        for
          forAuth <- makeToken(List("auth"))
          forCell <- makeToken(List(cell))
          accepted <- authorizeInternal(requestWith(forAuth)).exit
          refused <- outcome(forCell)
        yield assertTrue(accepted.isSuccess, refused._1.isFailure, refused._2.contains("internal_token_invalid"))
      }.provide(configLayer),
      test("a token made for central, or with no audience at all, is refused") {
        for
          forCentral <- makeToken(List("central"))
          noAudience <- makeToken(Nil)
          one <- outcome(forCentral)
          two <- outcome(noAudience)
        yield assertTrue(one._1.isFailure, two._1.isFailure)
      }.provide(configLayer),
      test("a token whose issuer is not central is refused, though central's key signed it") {
        for
          token <- makeToken(List("auth"), issuer = "auth")
          result <- outcome(token)
        yield assertTrue(result._1.isFailure, result._2.contains("internal_token_invalid"))
      }.provide(configLayer),
      test("a token signed by another key is refused, another cell's among them") {
        for
          byImpostor <- makeToken(List(cell), pair = TestEnvConfig.impostorKeyPair)
          // a cell's own key, under its own kid: central's key set has no such kid
          byCell <- makeToken(List(cell), pair = TestEnvConfig.anotherCellKeyPair)
          // this auth's own key: an auth must not be able to pass for central at another auth
          byAuth <- makeToken(List(cell), pair = TestEnvConfig.authKeyPair)
          one <- outcome(byImpostor)
          two <- outcome(byCell)
          three <- outcome(byAuth)
        yield assertTrue(one._1.isFailure, two._1.isFailure, three._1.isFailure, one._2.contains("internal_token_invalid"))
      }.provide(cellLayer),
      test("an HS256 token is refused, whatever it is signed with") {
        for
          token <- JWT.serialize(
            JWT.Claims("central", "central", List(cell), Json.Obj()),
            10.minutes,
            JWT.Signature.Symmetric(javax.crypto.spec.SecretKeySpec(Array.fill(32)(7.toByte), "HmacSHA256")),
          )
          result <- outcome(token)
        yield assertTrue(result._1.isFailure, result._2.contains("internal_token_invalid"))
      }.provide(cellLayer),
      test("a token signed PS256 with central's own key is refused: the algorithm is RS256 and nothing else") {
        for
          token <- JWT.serialize(
            JWT.Claims("central", "central", List(cell), Json.Obj()),
            10.minutes,
            JWT.Signature.Asymmetric(JWT.Algorithm.PS256, TestEnvConfig.centralKeyPair.keyId, TestEnvConfig.centralKeyPair.privateKey),
          )
          result <- outcome(token)
        yield assertTrue(result._1.isFailure)
      }.provide(cellLayer),
      test("an expired token is refused") {
        for
          token <- makeToken(List(cell), ttl = Duration.fromSeconds(-60))
          result <- outcome(token)
        yield assertTrue(result._1.isFailure, result._2.contains("internal_token_invalid"))
      }.provide(cellLayer),
      test("a token with no expiry is refused, though central's key signed it") {
        val claims = JWTClaimsSet.Builder().issuer("central").subject("central").audience(cell).jwtID("j-1").build()
        val jwt = SignedJWT(
          JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestEnvConfig.centralKeyPair.keyId).`type`(JOSEObjectType.JWT).build(),
          claims,
        )
        jwt.sign(RSASSASigner(TestEnvConfig.centralKeyPair.privateKey))
        for result <- outcome(jwt.serialize())
        yield assertTrue(result._1.isFailure)
      }.provide(cellLayer),
      test("a call with no token is refused with a code of its own") {
        for
          result <- logContext.locallyWith(identity)(
            authorizeInternal(zio.http.Request.get(zio.http.URL.empty)).exit.zip(logContext.get.map(_.get(Observability.error).map(_.code))),
          )
        yield assertTrue(result._1.isFailure, result._2.contains("internal_token_missing"))
      }.provide(cellLayer),
      test("a refused call names no actor and no administrator's address") {
        for
          token <- makeToken(List("kz-2"))
          result <- AuditContext.reset(AuditContext.State.empty)(
            authorizeInternal(requestWith(token).updateURL(_.addQueryParam(AdminIpParam, "203.0.113.7"))).exit.zip(AuditContext.current),
          )
        yield assertTrue(result._1.isFailure, result._2.actor.isEmpty)
      }.provide(cellLayer),
    ),
  ) @@ silentLogging
