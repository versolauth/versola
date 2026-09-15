package versola.oauth.client

import com.nimbusds.jose.jwk.RSAKey
import versola.auth.TestEnvConfig
import versola.util.JWT
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

/** [[EdgeRegistrySyncClient]] mirrors [[ResourceSyncClient]] (see [[ResourceSyncClientSpec]]),
  * but what it hands back -- a [[JWT.PublicKeys]] per edge -- has no useful notion of equality:
  * it wraps a Nimbus `JWKSet`, which compares by reference. So these verify it the way
  * [[EdgeAssertion.verify]] actually uses it: by deserializing a token signed with the key that
  * is supposed to be in there.
  */
object EdgeRegistrySyncClientSpec extends ZIOSpecDefault:

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  private def generate(): java.security.KeyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val currentKeyPair = generate()
  private val oldKeyPair = generate()

  private def jwk(keyId: String, publicKey: RSAPublicKey): Json.Obj =
    RSAKey.Builder(publicKey).keyID(keyId).build().toJSONString.fromJson[Json.Obj].getOrElse(Json.Obj())

  private val currentJwk = jwk("kid-current", currentKeyPair.getPublic.asInstanceOf[RSAPublicKey])
  private val oldJwk = jwk("kid-old", oldKeyPair.getPublic.asInstanceOf[RSAPublicKey])

  /** Mirrors the wire shape `EdgeController`'s registry endpoint actually serves. */
  private case class RegistryEntryMirror(
      id: String,
      publicKey: Json.Obj,
      oldPublicKey: Option[Json.Obj],
  ) derives JsonCodec

  private case class RegistryResponseMirror(edges: List[RegistryEntryMirror]) derives JsonCodec

  private def signedWith(keyPair: java.security.KeyPair, keyId: String): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(issuer = "edge-1", subject = "edge-1", audience = List("central"), custom = Json.Obj()),
      ttl = 5.minutes,
      signature = JWT.Signature.Asymmetric(
        algorithm = JWT.Algorithm.RS256,
        keyId = keyId,
        privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey],
      ),
    )

  private def respondWith(body: RegistryResponseMirror) =
    TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json(body.toJson))).toRoutes)

  def spec = suite("EdgeRegistrySyncClient")(
    test("requests central's edges registry and keys the result by edge id") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { r =>
            seen.set(Some(r)).as(Response.json(RegistryResponseMirror(List(RegistryEntryMirror("edge-1", currentJwk, None))).toJson))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = EdgeRegistrySyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        result <- service.getAll
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        token <- signedWith(currentKeyPair, "kid-current")
        verified <- JWT.deserialize[Json.Obj](token, result("edge-1"), JWT.Type.JWT).either
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/edges/registry"),
        result.keySet == Set("edge-1"),
        verified.isRight,
      )
    },
    // The pair auth needs while central is mid-rotation: the outgoing key still verifies
    // whatever it already signed, alongside the incoming one.
    test("keeps both halves of a rotation verifiable until central drops the old key") {
      for
        _ <- respondWith(RegistryResponseMirror(List(RegistryEntryMirror("edge-1", currentJwk, Some(oldJwk)))))
        client <- ZIO.service[Client]
        service = EdgeRegistrySyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        result <- service.getAll
        currentToken <- signedWith(currentKeyPair, "kid-current")
        oldToken <- signedWith(oldKeyPair, "kid-old")
        currentVerified <- JWT.deserialize[Json.Obj](currentToken, result("edge-1"), JWT.Type.JWT).either
        oldVerified <- JWT.deserialize[Json.Obj](oldToken, result("edge-1"), JWT.Type.JWT).either
      yield assertTrue(currentVerified.isRight, oldVerified.isRight)
    },
    test("returns an empty registry when central has no edges") {
      for
        _ <- respondWith(RegistryResponseMirror(List.empty))
        client <- ZIO.service[Client]
        service = EdgeRegistrySyncClient.Impl(TestEnvConfig.coreConfig, tokenService(client))
        result <- service.getAll
      yield assertTrue(result.isEmpty)
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
