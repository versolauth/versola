package versola.central.users

import versola.central.{CentralConfig, TestCentralConfig}
import versola.central.configuration.roles.RoleId
import versola.central.configuration.tenants.TenantId
import versola.util.{JWT, Patch}
import versola.util.Email
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID
import javax.crypto.spec.SecretKeySpec

object AuthClientSpec extends ZIOSpecDefault:

  private case class TokenClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey = TestCentralConfig.config.secretKey
  private val userId    = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val tenantId  = TenantId("t1")
  private val roleId    = RoleId("r1")

  /** Captures the Authorization header from the first captured request. */
  private def captureBearer(seen: Ref[Option[Request]]): Task[String] =
    seen.get.someOrFail(new RuntimeException("No request captured")).flatMap: req =>
      ZIO.fromOption(req.header(Header.Authorization).collect:
        case Header.Authorization.Bearer(t) => t.stringValue
      ).orElseFail(new RuntimeException("No Bearer header"))

  /** Assert the bearer is signed with the shared secret and has the expected claims. */
  private def assertBearerClaims(token: String): Task[TestResult] =
    JWT.deserialize[TokenClaims](token, secretKey)
      .mapError(e => new RuntimeException(e.toString))
      .map(claims =>
        assertTrue(
          claims.iss == "central",
          claims.sub == "central",
          claims.aud == List("auth"),
        )
      )

  /** Fixed token returned by the stub AuthTokenService. */
  private val fixedToken: Task[String] =
    JWT.serialize(
      claims = JWT.Claims("central", "central", List("auth"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(secretKey),
    )

  private def tokenServiceLayer: ULayer[AuthTokenService] =
    ZLayer.fromZIO(fixedToken.map(t => new AuthTokenService:
      override def getToken: UIO[String] = ZIO.succeed(t)
    ).orDie)

  private def authClientLayer: ZLayer[Client & CentralConfig & AuthTokenService, Nothing, AuthClient] =
    ZLayer.fromFunction(AuthClient.Impl(_, _, _))

  private def mkTest(name: String, call: AuthClient => Task[Unit]) =
    test(name) {
      for
        seen   <- Ref.make(Option.empty[Request])
        _      <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.status(Status.NoContent))).toRoutes
        )
        client <- ZIO.service[AuthClient]
        _      <- call(client)
        token  <- captureBearer(seen)
        result <- assertBearerClaims(token)
      yield result
    }.provide(TestClient.layer, ZLayer.succeed(TestCentralConfig.config), tokenServiceLayer, authClientLayer)

  def spec = suite("AuthClient")(
    mkTest("createUser sends Authorization: Bearer header with valid JWT",
      _.createUser(userId, None, None, None, Json.Obj())),
    mkTest("patchUser sends Authorization: Bearer header with valid JWT",
      _.patchUser(userId, None, None, None, None)),
    mkTest("assignRole sends Authorization: Bearer header with valid JWT",
      _.assignRole(userId, tenantId, roleId)),
    mkTest("removeRole sends Authorization: Bearer header with valid JWT",
      _.removeRole(userId, tenantId, roleId)),
  ) @@ TestAspect.silentLogging
