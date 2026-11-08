package versola.e2e.support

import zio.*
import zio.http.*
import zio.test.*

import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.util.{Base64, UUID}

object OAuthClientSpec extends ZIOSpecDefault:
  private val resourceSecret = "resource-secret-for-test"

  /** The config file of an edge, as the staged stack has one: central takes who made a call only from an edge's signature, so a call
    * the client makes to central is signed with the key in it. */
  private val edgeConf: Path =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val key = Base64.getEncoder.encodeToString(generator.generateKeyPair().getPrivate.getEncoded)
    Files.writeString(Files.createTempFile("edge-env", ".conf"), s"""id = "edge-default"\nkey-id = "kid-1"\nprivate-key = "$key"\n""")

  private val config = E2EConfig(
    authUrl = "http://auth.test",
    authAdditionalUrl = "http://auth-additional.test",
    authDiagnosticsUrl = "http://auth-diagnostics.test",
    centralUrl = "http://central.test",
    edgeUrl = "http://edge.test",
    adminLogin = "admin",
    adminPassword = "password",
    adminNewPassword = "new-password",
    clientId = "client",
    resourceSecret = resourceSecret,
    accountResourceSecret = "account-resource-secret-for-test",
    edgeInternalSecret = "edge-internal-secret-for-test",
    provisionerClientId = "utils",
    provisionerPrivateKey = "{}",
    redirectUri = "http://edge.test/complete",
    authMutualTlsUrl = "https://auth-mtls.test",
    authMutualTlsClientCertificate = "client.crt",
    authMutualTlsClientKey = "client.key",
    authMutualTlsTrustedCertificates = "ca.crt",
    centralLauncher = "central-postgres-impl",
    centralEnvConf = "env.conf",
    authLauncher = "auth-postgres-impl",
    authEnvConf = "env.conf",
    edgeLauncher = "edge-postgres-impl",
    edgeEnvConf = edgeConf.toString,
  )
  private val userId = UUID.fromString("018f0f2a-1c7b-7000-9000-000000000901")

  def spec = suite("OAuthClient")(
    test("authenticates Central requests with the central resource secret") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoute(
          Method.POST / "users" -> Handler.fromFunctionZIO[Request] { request =>
            seen.set(Some(request)).as(Response.json(s"""{"id":"$userId"}"""))
          },
        )
        client <- ZIO.service[Client]
        result <- OAuthClient(client, config).registerUser(email = Some("user@example.com"))
        request <- seen.get.someOrFail(RuntimeException("Central request was not captured"))
      yield assertTrue(
        result == userId,
        request.header(Header.Authorization).contains(
          Header.Authorization.Basic("central", resourceSecret),
        ),
        // and says who it is, signed, as edge does: central refuses a call that names no actor
        request.url.queryParams.getAll("actorId") == Chunk(E2EActor.clientId),
        request.url.queryParams.getAll("actorSignature").nonEmpty,
      )
    },
  ).provide(TestClient.layer)
