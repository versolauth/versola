package versola.edge

import versola.edge.model.{ClientCredential, ClientId, EdgeId, PermissionId}
import versola.util.{Base64, Secret, SecurityService, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.RSAKey

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

object OAuthClientsSyncClientSpec extends ZIOSpecDefault:
  private val decryptedSecretA = Array.fill(32)(3.toByte)
  private val syncToken = "sync-token"

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private val config = EdgeConfig(
    EdgeId("edge-1"),
    "edge-key",
    keyPair.getPrivate.nn,
    EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private def fakeSecurityService(decryptedByCiphertext: Map[String, Array[Byte]]): SecurityService =
    new SecurityService:
      override def encryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.dieMessage("Unused in test")
      override def decryptAes256(data: Array[Byte], key: javax.crypto.SecretKey) = ZIO.dieMessage("Unused in test")
      override def encryptRsa(data: Array[Byte], key: java.security.PublicKey) = ZIO.dieMessage("Unused in test")
      override def decryptRsa(data: Array[Byte], key: java.security.PrivateKey) = ZIO.dieMessage("Unused in test")
      override def decryptRsaHybrid(data: Array[Byte], key: java.security.PrivateKey) =
        ZIO.succeed(decryptedByCiphertext(Base64.urlEncode(data)))
      override def mac(macInput: Secret, key: Array[Byte]) = ZIO.dieMessage("Unused in test")
      override def hashPassword(pw: Secret, salt: versola.util.Salt, pepper: Secret.Bytes16) =
        ZIO.dieMessage("Unused in test")
      override def generateRsaKeyPair = ZIO.dieMessage("Unused in test")
      override def generateEcKeyPair = ZIO.dieMessage("Unused in test")

  private val centralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.succeed(syncToken)

  private case class SyncClientRecordMirror(
      id: ClientId,
      secret: Option[String],
      accessTokenTtl: Duration,
      permissions: Set[String] = Set.empty,
      requireSignedRequestObject: Boolean = false,
      requirePushedAuthorizationRequests: Boolean = false,
      edgeSigningKey: Option[String] = None,
      edgeClientCertificate: Option[String] = None,
  ) derives JsonCodec

  private case class SyncResponseMirror(clients: Vector[SyncClientRecordMirror]) derives JsonCodec


  private val signingKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  /** The private JWK central holds for a client an edge authenticates as by key. */
  private val signingKeyDocument =
    RSAKey.Builder(signingKeyPair.getPublic.nn.asInstanceOf[RSAPublicKey])
      .privateKey(signingKeyPair.getPrivate.nn)
      .keyID("client-key-1")
      .algorithm(JWSAlgorithm.PS256)
      .build().nn.toJSONString.nn

  private val signingSuite = suite("edge signing key")(
    test("decrypts the signing key and prefers it over a secret the same client also has") {
      val secretCiphertext = Base64.urlEncode(Array.fill(32)(40.toByte))
      val keyCiphertext = Base64.urlEncode(Array.fill(32)(41.toByte))
      val body = SyncResponseMirror(
        Vector(
          SyncClientRecordMirror(
            ClientId("both"),
            Some(secretCiphertext),
            15.minutes,
            requireSignedRequestObject = true,
            requirePushedAuthorizationRequests = true,
            edgeSigningKey = Some(keyCiphertext),
          ),
        ),
      ).toJson
      for
        _ <- TestClient.addRoutes(Handler.succeed(Response.json(body)).toRoutes)
        client <- ZIO.service[Client]
        service = OAuthClientsSyncClient.Impl(
          client,
          config,
          fakeSecurityService(Map(
            secretCiphertext -> decryptedSecretA,
            keyCiphertext -> signingKeyDocument.getBytes("UTF-8").nn,
          )),
          centralSyncTokenService,
        )
        clients <- service.getAll
        synced = clients(ClientId("both"))
      yield assertTrue(
        // The key is the stronger credential and the only one that can sign a request
        // object, so a client central sent both for must not fall back to the secret.
        synced.credential.signingKey.map(_.keyId) == Some("client-key-1"),
        synced.requireSignedRequestObject,
        synced.requirePushedAuthorizationRequests,
      )
    },
    test("keeps a client that has a signing key and no secret at all") {
      val keyCiphertext = Base64.urlEncode(Array.fill(32)(42.toByte))
      val body = SyncResponseMirror(
        Vector(SyncClientRecordMirror(ClientId("key-only"), None, 15.minutes, edgeSigningKey = Some(keyCiphertext))),
      ).toJson
      for
        _ <- TestClient.addRoutes(Handler.succeed(Response.json(body)).toRoutes)
        client <- ZIO.service[Client]
        service = OAuthClientsSyncClient.Impl(
          client,
          config,
          fakeSecurityService(Map(keyCiphertext -> signingKeyDocument.getBytes("UTF-8").nn)),
          centralSyncTokenService,
        )
        clients <- service.getAll
      yield assertTrue(clients.keySet == Set(ClientId("key-only")))
    },
    test("fails the whole sync on an unusable key rather than serving a doubtful snapshot") {
      val keyCiphertext = Base64.urlEncode(Array.fill(32)(43.toByte))
      val body = SyncResponseMirror(
        Vector(SyncClientRecordMirror(ClientId("broken"), None, 15.minutes, edgeSigningKey = Some(keyCiphertext))),
      ).toJson
      for
        _ <- TestClient.addRoutes(Handler.succeed(Response.json(body)).toRoutes)
        client <- ZIO.service[Client]
        service = OAuthClientsSyncClient.Impl(
          client,
          config,
          fakeSecurityService(Map(keyCiphertext -> "{\"kty\":\"oct\"}".getBytes("UTF-8").nn)),
          centralSyncTokenService,
        )
        error <- service.getAll.flip
      yield assertTrue(error.getMessage.nn.contains("edge signing key"))
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging

  /** The certificate central holds for a client an edge authenticates as by mutual TLS. */
  private val clientCertificate = TestCertificates.generate()

  private val certificateSuite = suite("edge client certificate")(
    test("decrypts the certificate and prefers it over every other credential") {
      val secretCiphertext = Base64.urlEncode(Array.fill(32)(50.toByte))
      val keyCiphertext = Base64.urlEncode(Array.fill(32)(51.toByte))
      val certificateCiphertext = Base64.urlEncode(Array.fill(32)(52.toByte))
      val body = SyncResponseMirror(
        Vector(
          SyncClientRecordMirror(
            ClientId("mtls"),
            Some(secretCiphertext),
            15.minutes,
            edgeSigningKey = Some(keyCiphertext),
            edgeClientCertificate = Some(certificateCiphertext),
          ),
        ),
      ).toJson
      for
        _ <- TestClient.addRoutes(Handler.succeed(Response.json(body)).toRoutes)
        client <- ZIO.service[Client]
        service = OAuthClientsSyncClient.Impl(
          client,
          config,
          fakeSecurityService(Map(
            secretCiphertext -> decryptedSecretA,
            keyCiphertext -> signingKeyDocument.getBytes("UTF-8").nn,
            certificateCiphertext -> clientCertificate.bundle.getBytes("UTF-8").nn,
          )),
          centralSyncTokenService,
        )
        clients <- service.getAll
        synced = clients(ClientId("mtls")).credential
      yield assertTrue(
        // A certificate registration makes mutual TLS the client's method, and auth refuses
        // an assertion or a secret from such a client -- so a fallback to either would
        // authenticate nothing.
        synced match
          case ClientCredential.MutualTls(material) =>
            material.leaf.getSubjectX500Principal.getName ==
              clientCertificate.certificate.getSubjectX500Principal.getName
          case _ => false,
      )
    },
    test("fails the whole sync on an unusable certificate rather than dropping the client") {
      val certificateCiphertext = Base64.urlEncode(Array.fill(32)(53.toByte))
      val body = SyncResponseMirror(
        Vector(
          SyncClientRecordMirror(
            ClientId("broken-certificate"),
            None,
            15.minutes,
            edgeClientCertificate = Some(certificateCiphertext),
          ),
        ),
      ).toJson
      for
        _ <- TestClient.addRoutes(Handler.succeed(Response.json(body)).toRoutes)
        client <- ZIO.service[Client]
        service = OAuthClientsSyncClient.Impl(
          client,
          config,
          fakeSecurityService(Map(
            certificateCiphertext -> clientCertificate.certificatePem.getBytes("UTF-8").nn,
          )),
          centralSyncTokenService,
        )
        error <- service.getAll.flip
      yield assertTrue(error.getMessage.nn.contains("edge client certificate"))
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging

  def spec = suite("OAuthClientsSyncClient")(
    signingSuite,
    certificateSuite,
    test("decrypts every client with a secret and drops clients missing one") {
      val secretACiphertext = Base64.urlEncode(Array.fill(32)(30.toByte))
      val body = SyncResponseMirror(
        Vector(
          SyncClientRecordMirror(ClientId("with-secret"), Some(secretACiphertext), 15.minutes, Set("oauth:read")),
          SyncClientRecordMirror(ClientId("no-secret"), None, 30.minutes),
        ),
      ).toJson
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(body))
          }.toRoutes,
        )
        client <- ZIO.service[Client]
        service = OAuthClientsSyncClient.Impl(
          client,
          config,
          fakeSecurityService(Map(secretACiphertext -> decryptedSecretA)),
          centralSyncTokenService,
        )
        clients <- service.getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.encode.contains("configuration/clients/sync"),
        request.header(Header.Authorization).contains(Header.Authorization.Bearer(syncToken)),
        clients.keySet == Set(ClientId("with-secret")),
        clients(ClientId("with-secret")).credential == ClientCredential.ClientSecret(Secret(decryptedSecretA)),
        clients(ClientId("with-secret")).permissions == Set(PermissionId("oauth:read")),
        clients(ClientId("with-secret")).accessTokenTtl == 15.minutes,
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
