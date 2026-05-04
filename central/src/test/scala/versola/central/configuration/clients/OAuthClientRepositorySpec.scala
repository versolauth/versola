package versola.central.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.{DatabaseSpecBase, RedirectUri, Secret}
import zio.*
import zio.prelude.EqualOps
import zio.test.*

trait OAuthClientRepositorySpec extends DatabaseSpecBase[OAuthClientRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantId = TenantId("tenant-a")
  val clientId = ClientId("web-app")
  val readScope = ScopeToken("read")
  val writeScope = ScopeToken("write")
  val readPermission = Permission("users:read")
  val writePermission = Permission("users:write")
  val redirectUri1 = RedirectUri("https://example.com/callback")
  val redirectUri2 = RedirectUri("https://example.com/updated")
  val secret1 = Secret(Array.fill(48)(1.toByte))
  val secret2 = Secret(Array.fill(48)(2.toByte))

  val client = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = "Web App",
    redirectUris = Set(redirectUri1),
    scope = Set(readScope),
    externalAudience = List(ClientId("api")),
    secret = Some(secret1),
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    permissions = Set(readPermission),
  )

  override def testCases(env: OAuthClientRepositorySpec.Env) =
    List(
      test("create and find client") {
        for
          _ <- env.repository.createClient(client)
          found <- env.repository.find(tenantId, clientId)
        yield assertTrue(
          found === Some(client)
        )
      },
      test("update client should preserve existing name when new name is absent") {
        for
          _ <- env.repository.createClient(client)
          _ <- env.repository.updateClient(
            tenantId = tenantId,
            clientId = clientId,
            clientName = Some("new-name"),
            patchRedirectUris = PatchClientRedirectUris(
              add = Set(redirectUri2),
              remove = Set(redirectUri1),
            ),
            patchScope = PatchClientScope(
              add = Set(writeScope),
              remove = Set(readScope),
            ),
            patchPermissions = PatchPermissions(
              add = Set(writePermission),
              remove = Set(readPermission),
            ),
            accessTokenTtl = Some(15.minutes),
          )
          found <- env.repository.find(tenantId, clientId)
        yield assertTrue(
          found === Some(
            client.copy(
              clientName = "new-name",
              redirectUris = Set(redirectUri2),
              scope = Set(writeScope),
              permissions = Set(writePermission),
              accessTokenTtl = 15.minutes,
            )
          )
        )
      },
      test("rotate secrets and delete client") {
        for
          _ <- env.repository.createClient(client)
          _ <- env.repository.rotateClientSecret(tenantId, clientId, secret2)
          rotated <- env.repository.find(tenantId, clientId)
          _ <- env.repository.deletePreviousClientSecret(tenantId, clientId)
          withoutPrevious <- env.repository.find(tenantId, clientId)
          _ <- env.repository.deleteClient(tenantId, clientId)
          deleted <- env.repository.find(tenantId, clientId)
        yield assertTrue(
          rotated === Some(
            client.copy(
              secret = Some(secret2),
              previousSecret = Some(secret1),
            )
          ),
          withoutPrevious === Some(
            client.copy(
              secret = Some(secret2),
              previousSecret = None,
            )
          ),
          deleted === None
        )
      },
    )

object OAuthClientRepositorySpec:
  case class Env(repository: OAuthClientRepository)