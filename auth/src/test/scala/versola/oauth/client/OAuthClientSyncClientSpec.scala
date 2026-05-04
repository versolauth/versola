package versola.oauth.client

import versola.auth.TestEnvConfig
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.prelude.NonEmptySet
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object OAuthClientSyncClientSpec extends ZIOSpecDefault:
  private case class SignedClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey = SecretKeySpec(Array.fill(32)(6.toByte), "AES")
  private val config = TestEnvConfig.coreConfig.copy(central = CoreConfig.CentralSyncConfig(URL.empty, secretKey))
  private val configLayer = ZLayer.succeed(config)

  private given JsonCodec[NonEmptySet[String]] =
    JsonCodec.nonEmptyChunk[String].transform(NonEmptySet.fromNonEmptyChunk, _.toNonEmptyChunk)

  private case class EncodedClient(
      id: ClientId,
      clientName: String,
      redirectUris: NonEmptySet[String],
      scope: Set[ScopeToken],
      externalAudience: List[ClientId],
      secret: Option[String],
      previousSecret: Option[String],
      accessTokenTtl: Duration,
  ) derives JsonCodec
  private case class EncodedClientsWithPepper(clients: Vector[EncodedClient], pepper: String) derives JsonCodec

  private val tokenLayer = ZLayer.fromZIO(
    JWT.serialize(
      JWT.Claims("auth", "internal-auth", List("central"), Json.Obj()),
      10.minutes,
      JWT.Signature.Symmetric(secretKey),
    ).map(token => new CentralSyncTokenService:
      override def getToken: UIO[String] = ZIO.succeed(token)
    )
  )

  private val securityLayer = ZLayer.succeed(new SecurityService:
    override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.succeed(data)
    override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.succeed(data)
    override def mac(secret: Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
    override def hashPassword(password: Secret, salt: versola.util.Salt, pepper: Secret.Bytes16) = ZIO.dieMessage("Unused in test")
    override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")
  )

  def spec = suite("OAuthClientsClient")(
    test("fetch synced clients with bearer token and map decrypted secrets") {
      val currentSecret = Secret.fromString("current-secret")
      val previousSecret = Secret.fromString("previous-secret")
      val pepper = Secret.fromString("pepper-123")
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(
              Response.json(
                EncodedClientsWithPepper(
                  Vector(
                    EncodedClient(
                      ClientId("web-app"),
                      "Web App",
                      NonEmptySet("https://example.com/callback"),
                      Set(ScopeToken.OpenId, ScopeToken("profile")),
                      List(ClientId("api")),
                      Some(Base64Url.encode(currentSecret)),
                      Some(Base64Url.encode(previousSecret)),
                      300.seconds,
                    )
                  ),
                  Base64Url.encode(pepper),
                ).toJson
              )
            )
          }.toRoutes
        )
        service <- ZIO.service[OAuthClientSyncClient]
        result <- service.getAll
        request <- seen.get.someOrFail(new RuntimeException("No request captured"))
        token <- ZIO.fromOption(request.header(Header.Authorization).collect { case Header.Authorization.Bearer(v) => v.stringValue })
          .orElseFail(new RuntimeException("Missing bearer token"))
        claims <- JWT.deserialize[SignedClaims](token, secretKey).mapError(e => new RuntimeException(e.toString))
        client = result.clients(ClientId("web-app"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.encode.contains("v1/configuration/clients/sync"),
        claims.iss == "auth",
        claims.sub == "internal-auth",
        claims.aud == List("central"),
        result.pepper.sameElements(pepper),
        result.clients.size == 1,
        client.scope == Set(ScopeToken.OpenId, ScopeToken("profile")),
        client.externalAudience == List(ClientId("api")),
        client.secret.exists(_.sameElements(currentSecret)),
        client.previousSecret.exists(_.sameElements(previousSecret)),
        client.accessTokenTtl == 300.seconds,
      )
    },
  ).provideShared(TestClient.layer, configLayer, tokenLayer, securityLayer, OAuthClientSyncClient.live) @@ TestAspect.silentLogging
