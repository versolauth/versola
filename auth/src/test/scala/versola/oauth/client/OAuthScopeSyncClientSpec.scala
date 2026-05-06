package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{Claim, ClaimRecord, ScopeRecord, ScopeToken}
import versola.util.{CoreConfig, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object OAuthScopeSyncClientSpec extends ZIOSpecDefault:
  private case class SignedClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey = SecretKeySpec(Array.fill(32)(12.toByte), "AES")
  private val configLayer = ZLayer.succeed(
    TestEnvConfig.coreConfig.copy(central = CoreConfig.CentralSyncConfig(URL.empty, secretKey))
  )
  private val tokenLayer = ZLayer.fromZIO(
    JWT.serialize(
      claims = JWT.Claims("auth", "internal-auth", List("central"), Json.Obj()),
      ttl = 10.minutes,
      signature = JWT.Signature.Symmetric(secretKey),
    ).map(token => new CentralSyncTokenService:
      override def getToken: UIO[String] = ZIO.succeed(token)
    )
  )

  def spec = suite("OAuthScopesClient")(
    test("fetch synced scopes with bearer token and map claims") {
      val expectedScopes = Vector(
        ScopeRecord(ScopeToken("profile"), Vector(ClaimRecord(Claim("email")), ClaimRecord(Claim("name"))))
      )
      // Wrap in the response object that matches what the client expects
      val responseBody = s"""{"scopes":${expectedScopes.toJson}}"""

      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](request =>
            seen.set(Some(request)).as(Response.text(responseBody).addHeader(Header.ContentType(zio.http.MediaType.application.json)))
          ).toRoutes
        )
        service <- ZIO.service[OAuthScopeSyncClient]
        scopes <- service.getAll
        request <- seen.get.someOrFail(new RuntimeException("No request captured"))
        token <- ZIO.fromOption(request.header(Header.Authorization).collect { case Header.Authorization.Bearer(v) => v.stringValue })
          .orElseFail(new RuntimeException("Missing bearer token"))
        claims <- JWT.deserialize[SignedClaims](token, secretKey).mapError(e => new RuntimeException(e.toString))
      yield assertTrue(
        request.method == Method.GET,
        request.url.encode.contains("v1/configuration/scopes/sync"),
        claims.iss == "auth",
        claims.sub == "internal-auth",
        claims.aud == List("central"),
        scopes == expectedScopes,
      )
    },
  ).provideShared(TestClient.layer, configLayer, tokenLayer, OAuthScopeSyncClient.live) @@ TestAspect.silentLogging
