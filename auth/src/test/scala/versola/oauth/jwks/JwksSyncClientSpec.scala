package versola.oauth.jwks

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.util.{Base64, CoreConfig, JWT, SecureRandom, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import javax.crypto.spec.SecretKeySpec

object JwksSyncClientSpec extends ZIOSpecDefault:
  private case class SignedClaims(iss: String, sub: String, aud: List[String]) derives JsonDecoder

  private val secretKey   = SecretKeySpec(Array.fill(32)(12.toByte), "AES")
  private val configLayer = ZLayer.succeed(
    TestEnvConfig.coreConfig.copy(central = CoreConfig.CentralSyncConfig(URL.empty, secretKey))
  )

  private val tokenLayer: ZLayer[Client, Throwable, CentralSyncTokenService] = ZLayer.fromZIO(
    for
      client <- ZIO.service[Client]
      token  <- JWT.serialize(
        JWT.Claims("auth", "internal-auth", List("central"), Json.Obj()),
        10.minutes,
        JWT.Signature.Symmetric(secretKey),
      )
    yield new CentralSyncTokenService:
      override def getToken: UIO[String] = ZIO.succeed(token)
      override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] =
        client.request(request.addHeader(Header.Authorization.Bearer(token)))
  )

  /** Central publishes the private halves encrypted under the shared sync secret, so the
    * client has to be given something it can actually decrypt to be tested at all.
    */
  private def signingKeysResponse(kid: String) =
    for
      security <- ZIO.service[SecurityService]
      encrypted <- security.encryptAes256(TestEnvConfig.privateKey.getEncoded.nn, secretKey)
    yield Json.Obj("privateKeys" -> Json.Obj(kid -> Json.Str(Base64.urlEncode(encrypted))))

  private def routes(seen: Ref[List[Request]], signingKeys: Json.Obj) =
    TestClient.addRoutes(
      Handler.fromFunctionZIO[Request] { request =>
        seen.update(_ :+ request).as(
          if request.url.encode.contains("signing-keys") then Response.json(signingKeys.toJson)
          else Response.json(TestEnvConfig.jwksJson.toJson),
        )
      }.toRoutes,
    )

  def spec = suite("JwksSyncClient")(
    test("fetches JWKS from central /configuration/jwks/sync with a bearer sync token") {
      for
        seen   <- Ref.make(List.empty[Request])
        keys   <- signingKeysResponse("test-key-id")
        _      <- routes(seen, keys)
        client  <- ZIO.service[JwksSyncClient]
        result  <- client.getAll
        requests <- seen.get
        request <- ZIO.fromOption(requests.find(_.url.encode.contains("configuration/jwks/sync")))
          .orElseFail(RuntimeException("No JWKS request captured"))
        token   <- ZIO
          .fromOption(request.header(Header.Authorization).collect { case Header.Authorization.Bearer(v) => v.stringValue })
          .orElseFail(new RuntimeException("Missing bearer token"))
        claims  <- JWT.deserialize[SignedClaims](token, secretKey, JWT.Type.JWT).mapError(e => new RuntimeException(e.toString))
      yield assertTrue(
        request.method == Method.GET,
        claims.iss == "auth",
        claims.sub == "internal-auth",
        claims.aud == List("central"),
        result.publicKeys.keys.size() == 1,
      )
    },
    // The kid, the published key and the private half all have to arrive together for
    // anything to be signable; a private half that cannot be decrypted is the same as a
    // key auth does not have.
    test("decrypts the private halves central publishes, keyed by kid") {
      for
        seen <- Ref.make(List.empty[Request])
        keys <- signingKeysResponse("test-key-id")
        _ <- routes(seen, keys)
        client <- ZIO.service[JwksSyncClient]
        result <- client.getAll
        requests <- seen.get
      yield assertTrue(
        requests.exists(_.url.encode.contains("configuration/jwks/signing-keys/sync")),
        result.privateKeys.keySet == Set("test-key-id"),
        result.privateKeys("test-key-id").getEncoded.nn.sameElements(TestEnvConfig.privateKey.getEncoded.nn),
      )
    },
    test("fails rather than dropping a private half it cannot decrypt") {
      for
        seen <- Ref.make(List.empty[Request])
        _ <- routes(seen, Json.Obj("privateKeys" -> Json.Obj("test-key-id" -> Json.Str("not-encrypted-anything"))))
        client <- ZIO.service[JwksSyncClient]
        exit <- ZIO.serviceWithZIO[JwksSyncClient](_.getAll).exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.exists(_.squashTrace.getMessage.contains("test-key-id")),
      )
    },
  ).provideShared(
    TestClient.layer,
    configLayer,
    tokenLayer,
    SecureRandom.live,
    SecurityService.live,
    JwksSyncClient.live,
  ) @@ TestAspect.silentLogging @@ TestAspect.sequential
