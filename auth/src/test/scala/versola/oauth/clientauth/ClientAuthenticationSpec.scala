package versola.oauth.clientauth

import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.ZIOStubs
import versola.auth.TestEnvConfig
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ClientIdWithAssertion, ClientIdWithSecret, MutualTlsAuth, OAuthClientRecord}
import versola.util.{ClientAssertion, JsonWebKeySet, Secret}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.time.Instant
import java.util.Date

/** RFC 7523 §2.2 `private_key_jwt` and RFC 8705 §2.2 `self_signed_tls_client_auth` as the
  * authenticator sees them: which credential a client's registration makes acceptable -- the
  * two read the same `jwks` column and must not be interchangeable -- and what the replay
  * guard's answer does to the request.
  *
  * The assertion service under test is the real one -- the rule being checked is how
  * verification and replay combine into an authentication decision, so only the repository the
  * replay guard reads is stood in for.
  */
object ClientAuthenticationSpec extends ZIOSpecDefault, ZIOStubs:

  private val keyPairGenerator = KeyPairGenerator.getInstance("EC")
  keyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val keyPair = keyPairGenerator.generateKeyPair()
  private val privateKey = keyPair.getPrivate.asInstanceOf[ECPrivateKey]
  private val jwk = ECKey.Builder(Curve.P_256, keyPair.getPublic.asInstanceOf[ECPublicKey]).keyID("ec-1").build()

  private val clientId = ClientId("assertion-client")
  private val issuer = TestEnvConfig.coreConfig.jwt.issuer

  private val keySet = JsonWebKeySet(
    Json.Obj("keys" -> Json.Arr(jwk.toJSONString.fromJson[Json.Obj].toOption.get)),
  )

  /** A client that authenticates by assertion. It keeps the secret every `web` client is
    * issued, which is what makes "the secret is no longer accepted" a rule to check rather
    * than a consequence of there being none. */
  private val assertionClient = TestEnvConfig.mtlsClient(clientId).copy(
    mtlsAuth = None,
    secret = Some(Secret(Array.fill(32)(1.toByte))),
    jwks = Some(keySet),
  )

  private def assertion(
      now: Instant,
      audience: String = s"$issuer/token",
      jti: String = "jti-1",
      subject: String = clientId,
  ): String =
    val claims = JWTClaimsSet.Builder()
      .issuer(subject)
      .subject(subject)
      .audience(audience)
      .jwtID(jti)
      .expirationTime(Date.from(now.plusSeconds(60)))
      .build()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec-1").build(), claims)
    jwt.sign(ECDSASigner(privateKey))
    jwt.serialize()

  /** Remembers what it is given, which is all the authenticator's decision depends on. */
  private class RecordingRepository(seen: Ref[Set[(String, String)]]) extends ClientAssertionRepository:
    override def recordIfAbsent(clientId: String, jti: String, expiresAt: Instant): Task[Boolean] =
      seen.modify(recorded => (!recorded((clientId, jti)), recorded + ((clientId, jti))))

  private def authentication(
      client: Option[OAuthClientRecord],
      repository: ClientAssertionRepository,
  ) =
    val configuration = stub[OAuthConfigurationService]
    configuration.find.returnsWith(ZIO.succeed(client))
    configuration.verifySecret.returnsWith(ZIO.succeed(client))
    configuration.getClientAssertionSigningAlgorithms.returnsWith(
      ZIO.succeed(ClientAssertion.Algorithm.Default),
    )
    configuration.getClientAssertionMaxLifetime.returnsWith(ZIO.succeed(5.minutes))
    ClientAuthentication.Impl(
      configuration,
      ClientAssertionService.Impl(repository, configuration),
      TestEnvConfig.coreConfig,
    )

  private def recording = Ref.make(Set.empty[(String, String)]).map(RecordingRepository(_))

  /** A client that authenticates by RFC 8705 §2.2: its certificate's public key is one of the
    * keys it registered, and there is no subject value anywhere in its registration. */
  private val selfSignedClient = TestEnvConfig.mtlsClient(clientId).copy(
    mtlsAuth = Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
    jwks = Some(TestEnvConfig.clientCertificateKeySet),
  )

  def spec = suite("ClientAuthentication")(
    test("authenticates a client whose registered keys verify the assertion it presented") {
      for
        now <- Clock.instant
        repository <- recording
        result <- authentication(Some(assertionClient), repository).authenticate(
          ClientIdWithAssertion(clientId, assertion(now)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result.map(_.id) == Right(clientId))
    },
    test("accepts an assertion addressed to the endpoint it arrived at, and not to another") {
      for
        now <- Clock.instant
        repository <- recording
        authenticator = authentication(Some(assertionClient), repository)
        atPar <- authenticator.authenticate(
          ClientIdWithAssertion(clientId, assertion(now, audience = s"$issuer/par")),
          certificate = None,
          endpoint = AuthenticatedEndpoint.PushedAuthorizationRequest,
        ).either
        // The same assertion at a different endpoint: `aud` names a server this request did
        // not reach, which is what RFC 7523 §3's audience check is for.
        atRevocation <- authenticator.authenticate(
          ClientIdWithAssertion(clientId, assertion(now, audience = s"$issuer/par", jti = "jti-2")),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Revocation,
        ).either
      yield assertTrue(atPar.isRight, atRevocation == Left(()))
    },
    test("accepts the issuer identifier as the audience, which the OAuth security BCP reads it as") {
      for
        now <- Clock.instant
        repository <- recording
        result <- authentication(Some(assertionClient), repository).authenticate(
          ClientIdWithAssertion(clientId, assertion(now, audience = issuer)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result.isRight)
    },
    test("refuses an assertion for a client that registered no keys, however well it verifies") {
      for
        now <- Clock.instant
        repository <- recording
        result <- authentication(
          Some(assertionClient.copy(jwks = None)),
          repository,
        ).authenticate(
          ClientIdWithAssertion(clientId, assertion(now)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result == Left(()))
    },
    test("refuses the secret of a client that registered keys, so the method cannot be declined") {
      for
        repository <- recording
        result <- authentication(Some(assertionClient), repository).authenticate(
          ClientIdWithSecret(clientId, assertionClient.secret),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result == Left(()))
    },
    test("refuses a jti the replay guard has already recorded for that client") {
      for
        now <- Clock.instant
        repository <- recording
        authenticator = authentication(Some(assertionClient), repository)
        first <- authenticator.authenticate(
          ClientIdWithAssertion(clientId, assertion(now)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
        replay <- authenticator.authenticate(
          ClientIdWithAssertion(clientId, assertion(now)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(first.isRight, replay == Left(()))
    },
    test("reports an unreachable replay guard as this server's failure, not the client's") {
      val unreachable = new ClientAssertionRepository:
        override def recordIfAbsent(clientId: String, jti: String, expiresAt: Instant): Task[Boolean] =
          ZIO.fail(RuntimeException("no connection"))

      for
        now <- Clock.instant
        result <- authentication(Some(assertionClient), unreachable).authenticate(
          ClientIdWithAssertion(clientId, assertion(now)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result.left.exists(_.isInstanceOf[Throwable]))
    },
    test("authenticates a self-signed client whose certificate carries a key it registered") {
      for
        repository <- recording
        result <- authentication(Some(selfSignedClient), repository).authenticate(
          ClientIdWithSecret(clientId, None),
          certificate = Some(TestEnvConfig.clientCertificate),
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result.map(_.id) == Right(clientId))
        .label("RFC 8705 §2.2: the key is the credential, so no subject is compared")
    },
    test("refuses a self-signed client presenting a certificate whose key it never registered") {
      for
        repository <- recording
        result <- authentication(Some(selfSignedClient), repository).authenticate(
          ClientIdWithSecret(clientId, None),
          certificate = Some(TestEnvConfig.otherClientCertificate),
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result == Left(()))
    },
    test("refuses a self-signed client that presented no certificate at all") {
      for
        repository <- recording
        result <- authentication(Some(selfSignedClient), repository).authenticate(
          ClientIdWithSecret(clientId, None),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result == Left(()))
    },
    test("refuses an assertion signed with the keys a self-signed client registered for §2.2") {
      for
        now <- Clock.instant
        repository <- recording
        result <- authentication(
          Some(selfSignedClient.copy(jwks = Some(keySet))),
          repository,
        ).authenticate(
          ClientIdWithAssertion(clientId, assertion(now)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(result == Left(()))
        .label("those keys are matched against a certificate; an assertion is a second credential")
    },
    test("still matches a §2.1 client by subject, keys or no keys in the picture") {
      for
        repository <- recording
        authenticator = authentication(Some(TestEnvConfig.mtlsClient(clientId)), repository)
        matched <- authenticator.authenticate(
          ClientIdWithSecret(clientId, None),
          certificate = Some(TestEnvConfig.clientCertificate),
          endpoint = AuthenticatedEndpoint.Token,
        ).either
        mismatched <- authenticator.authenticate(
          ClientIdWithSecret(clientId, None),
          certificate = Some(TestEnvConfig.otherClientCertificate),
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(matched.map(_.id) == Right(clientId), mismatched == Left(()))
    },
    test("records the jti against the client, so two clients may use the same one") {
      val otherId = ClientId("other-assertion-client")
      for
        now <- Clock.instant
        repository <- recording
        mine <- authentication(Some(assertionClient), repository).authenticate(
          ClientIdWithAssertion(clientId, assertion(now)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
        theirs <- authentication(
          Some(assertionClient.copy(id = otherId)),
          repository,
        ).authenticate(
          ClientIdWithAssertion(otherId, assertion(now, subject = otherId)),
          certificate = None,
          endpoint = AuthenticatedEndpoint.Token,
        ).either
      yield assertTrue(mine.isRight, theirs.isRight)
    },
  ) @@ TestAspect.silentLogging
