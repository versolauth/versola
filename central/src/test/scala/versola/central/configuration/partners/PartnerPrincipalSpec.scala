package versola.central.configuration.partners

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.clients.ClientId
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.util.{JWT, RsaKeyPair}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

/** `Versola-Principal` (#549 decision 4): the staff token is good only when auth signed it, it is an access token that has
  * not expired, it names this issuer and the partner's console client. Every other token is one mismatch, counted. */
object PartnerPrincipalSpec extends ZIOSpecDefault, ZIOStubs:

  private val console = ClientId("acme-console")
  private val issuer = "https://id.example.com"

  private def rsaKeyPair(keyId: String): RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(keyId = keyId, publicKey = pair.getPublic.asInstanceOf[RSAPublicKey], privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey])

  private val authKey = rsaKeyPair("auth-key")
  private val strangerKey = rsaKeyPair("stranger-key")

  private def token(
      key: RsaKeyPair = authKey,
      iss: String = issuer,
      clientId: String = console,
      sub: String = "staff-1",
      typ: JWT.Type = JWT.Type.AccessToken,
      ttl: Duration = 5.minutes,
  ): Task[String] =
    JWT.serialize(
      JWT.Claims(iss, sub, List("partner-api"), Json.Obj("client_id" -> Json.Str(clientId))),
      ttl,
      JWT.Signature.Asymmetric(JWT.Algorithm.RS256, key.keyId, key.privateKey),
      typ = typ,
    )

  private def verify(raw: Option[String], metadata: Option[Json.Obj] = Some(Json.Obj("issuer" -> Json.Str(issuer)))) =
    val jwks = stub[JwksService]
    val server = stub[ServerMetadataService]
    for
      _ <- jwks.getPublicKeys.succeedsWith(JWT.PublicKeys.fromJson(Json.Obj("keys" -> Json.Arr(authKey.toPublicJwk))))
      _ <- server.getMetadata.succeedsWith(metadata)
      result <- PartnerPrincipal.verify(
        raw,
        console,
      ).either.provideEnvironment(ZEnvironment[JwksService](jwks) ++ ZEnvironment[ServerMetadataService](server))
    yield result

  def spec = suite("PartnerPrincipal")(
    test("a live access token of the console client from this issuer names the staff member") {
      for
        raw <- token()
        result <- verify(Some(raw))
      yield assertTrue(result == Right(PartnerPrincipal.Principal("staff-1")))
    },
    test("no header is a mismatch") {
      verify(None).map(result => assertTrue(result == Left(PartnerPrincipal.Mismatch)))
    },
    test("a token of another client is a mismatch: the console client is what ties the person to the partner") {
      token(clientId = "acme-shop").flatMap(raw => verify(Some(raw))).map(result => assertTrue(result == Left(PartnerPrincipal.Mismatch)))
    },
    test("a token from another issuer is a mismatch, and so is a token when the issuer is not configured") {
      for
        raw <- token(iss = "https://other.example.com")
        other <- verify(Some(raw))
        good <- token()
        unconfigured <- verify(Some(good), metadata = None)
      yield assertTrue(other == Left(PartnerPrincipal.Mismatch), unconfigured == Left(PartnerPrincipal.Mismatch))
    },
    test("a token signed with a key auth does not sign with is a mismatch") {
      token(key = strangerKey).flatMap(raw => verify(Some(raw))).map(result => assertTrue(result == Left(PartnerPrincipal.Mismatch)))
    },
    test("an expired token, or one that is not an access token, is a mismatch") {
      for
        expired <- token(ttl = 1.minute)
        _ <- TestClock.adjust(2.minutes)
        late <- verify(Some(expired))
        plain <- token(typ = JWT.Type.JWT)
        wrongType <- verify(Some(plain))
      yield assertTrue(late == Left(PartnerPrincipal.Mismatch), wrongType == Left(PartnerPrincipal.Mismatch))
    },
  ) @@ TestAspect.silentLogging
