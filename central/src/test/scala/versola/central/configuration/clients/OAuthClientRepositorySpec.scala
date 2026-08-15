package versola.central.configuration.clients

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{PatchClientRedirectUris, PatchClientScope, PatchPermissions}
import versola.util.{DatabaseSpecBase, Patch, RedirectUri, Secret}
import zio.*
import zio.http.URL
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
  val frontLogoutUri = URL.decode("https://example.com/front-logout").toOption.get
  val backLogoutUri = URL.decode("https://example.com/back-logout").toOption.get

  val client = OAuthClientRecord(
    id = clientId,
    tenantId = tenantId,
    clientName = "Web App",
    redirectUris = Set(redirectUri1),
    scope = Set(readScope),
    secret = Some(secret1),
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 7776000.seconds,
    permissions = Set(readPermission),
    theme = "default",
    authFlow = Some(AuthFlow.default),
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
  )

  /** Applies a registration-flow patch, leaving every other field of the client alone. */
  private def patchNullableFields(
      env: OAuthClientRepositorySpec.Env,
      registrationFlow: Option[Patch[RegistrationFlow]] = None,
      authFlow: Option[Patch[AuthFlow]] = None,
      frontChannelLogoutUri: Option[Patch[URL]] = None,
      backChannelLogoutUri: Option[Patch[URL]] = None,
  ) =
    env.repository.updateClient(
      clientId = clientId,
      clientName = None,
      patchRedirectUris = PatchClientRedirectUris(add = Set.empty, remove = Set.empty),
      patchScope = PatchClientScope(add = Set.empty, remove = Set.empty),
      patchPermissions = PatchPermissions(add = Set.empty, remove = Set.empty),
      accessTokenTtl = None,
      refreshTokenTtl = None,
      theme = None,
      authFlow = authFlow,
      registrationFlow = registrationFlow,
      otpTemplateId = None,
      frontChannelLogoutUri = frontChannelLogoutUri,
      frontChannelLogoutSessionRequired = None,
      backChannelLogoutUri = backChannelLogoutUri,
    )

  private def patchRegistrationFlow(
      env: OAuthClientRepositorySpec.Env,
      registrationFlow: Option[Patch[RegistrationFlow]],
  ) =
    patchNullableFields(env, registrationFlow = registrationFlow)

  override def testCases(env: OAuthClientRepositorySpec.Env) =
    List(
      test("create and find client") {
        for
          _ <- env.repository.createClient(client)
          found <- env.repository.find(clientId)
        yield assertTrue(
          found === Some(client)
        )
      },
      test("create client twice should fail with ClientAlreadyExists") {
        for
          _ <- env.repository.createClient(client)
          error <- env.repository.createClient(client).flip
        yield assertTrue(
          error == ClientAlreadyExists(clientId)
        )
      },
      test("update client should preserve existing name when new name is absent") {
        for
          _ <- env.repository.createClient(client)
          _ <- env.repository.updateClient(
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
            refreshTokenTtl = None,
            theme = None,
            authFlow = None,
            registrationFlow = None,
            otpTemplateId = None,
            frontChannelLogoutUri = None,
            frontChannelLogoutSessionRequired = None,
            backChannelLogoutUri = None,
          )
          found <- env.repository.find(clientId)
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
      test("create and find client with a registration flow") {
        val registering = client.copy(registrationFlow = Some(RegistrationFlow.default))

        for
          _ <- env.repository.createClient(registering)
          found <- env.repository.find(clientId)
        yield assertTrue(
          found === Some(registering)
        )
      },
      test("update client should leave the registration flow untouched when the patch is absent") {
        val registering = client.copy(registrationFlow = Some(RegistrationFlow.default))

        for
          _ <- env.repository.createClient(registering)
          _ <- patchRegistrationFlow(env, None)
          found <- env.repository.find(clientId)
        yield assertTrue(
          found.flatMap(_.registrationFlow) === Some(RegistrationFlow.default)
        )
      },
      test("update client should clear the registration flow when the patch is an explicit None") {
        val registering = client.copy(registrationFlow = Some(RegistrationFlow.default))

        for
          _ <- env.repository.createClient(registering)
          _ <- patchRegistrationFlow(env, Some(Patch.Deleted))
          found <- env.repository.find(clientId)
        yield assertTrue(
          found.map(_.registrationFlow) === Some(None)
        )
      },
      test("update client should replace the registration flow when the patch carries one") {
        val updated = RegistrationFlow(
            credential = RegistrationCredential.phone,
          steps = List(RegistrationStep.Otp(), RegistrationStep.SetPassword()),
            roleIds = Set(RegistrationFlow.defaultRoleId),
        )

        for
          _ <- env.repository.createClient(client)
          _ <- patchRegistrationFlow(env, Some(Patch.Modified(updated)))
          found <- env.repository.find(clientId)
        yield assertTrue(
          found.flatMap(_.registrationFlow) === Some(updated)
        )
      },
      test("update client should leave the auth flow untouched when the patch is absent") {
        for
          _ <- env.repository.createClient(client)
          _ <- patchNullableFields(env, authFlow = None)
          found <- env.repository.find(clientId)
        yield assertTrue(
          found.flatMap(_.authFlow) === Some(AuthFlow.default)
        )
      },
      test("update client should clear the auth flow when the patch is an explicit delete") {
        for
          _ <- env.repository.createClient(client)
          _ <- patchNullableFields(env, authFlow = Some(Patch.Deleted))
          found <- env.repository.find(clientId)
        yield assertTrue(
          found.map(_.authFlow) === Some(None)
        )
      },
      test("update client should clear the logout URIs when the patch is an explicit delete") {
        val withLogout = client.copy(frontChannelLogoutUri = Some(frontLogoutUri))

        for
          _ <- env.repository.createClient(withLogout)
          _ <- patchNullableFields(env, frontChannelLogoutUri = Some(Patch.Deleted))
          found <- env.repository.find(clientId)
        yield assertTrue(
          found.map(_.frontChannelLogoutUri) === Some(None)
        )
      },
      test("update client should swap the logout channel when one is cleared and the other set") {
        val withLogout = client.copy(frontChannelLogoutUri = Some(frontLogoutUri))

        for
          _ <- env.repository.createClient(withLogout)
          _ <- patchNullableFields(
                 env,
                 frontChannelLogoutUri = Some(Patch.Deleted),
                 backChannelLogoutUri = Some(Patch.Modified(backLogoutUri)),
               )
          found <- env.repository.find(clientId)
        yield assertTrue(
          found.map(_.frontChannelLogoutUri) === Some(None),
          found.flatMap(_.backChannelLogoutUri) === Some(backLogoutUri),
        )
      },
      test("rotate secrets and delete client") {
        for
          _ <- env.repository.createClient(client)
          _ <- env.repository.rotateClientSecret(clientId, secret2)
          rotated <- env.repository.find(clientId)
          _ <- env.repository.deletePreviousClientSecret(clientId)
          withoutPrevious <- env.repository.find(clientId)
          _ <- env.repository.deleteClient(clientId)
          deleted <- env.repository.find(clientId)
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