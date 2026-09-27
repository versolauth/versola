package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** #353: the FAPI 2.0 tenant profile as central's admin API enforces it -- the default for a new
  * tenant, the registrations and patches it refuses, and a switch onto it that is refused while
  * any client of the tenant would violate it.
  *
  * Each test creates its own tenant: `default` is put on `standard` for the rest of the suite
  * (see [[SecurityProfiles]]), and a tenant is created on FAPI 2.0.
  */
object SecurityProfileApiSpec extends CentralApiSpec:

  private val clients = "/configuration/clients"
  private val tenants = "/configuration/tenants"

  private def profileOf(central: CentralApi, tenantId: String): Task[Option[String]] =
    eventually(
      central.get("/configuration/challenges/challenge-settings", "tenantId" -> tenantId)
        .flatMap(_.obj)
        .map(_.obj("settings").flatMap(_.str("securityProfile"))),
    )(_.isDefined)

  private def withTenant[A](test: (CentralApi, String) => Task[A]): RIO[CentralApi, A] =
    for
      central <- api
      tenantId <- CentralApi.id("e2e-fapi")
      _ <- central.post(tenants, Fixtures.tenant(tenantId))
      _ <- profileOf(central, tenantId)
      result <- test(central, tenantId).ensuring(central.delete(tenants, "tenantId" -> tenantId).ignore)
    yield result

  /** What FAPI 2.0 admits for a client an edge does not front: `private_key_jwt`, DPoP-bound
    * tokens (whose TTL floor is an hour), PAR, https redirects. */
  private def conformantClient(id: String, tenantId: String, signer: AssertionSigner): Json.Obj =
    Fixtures.client(
      id,
      tenantId = tenantId,
      redirectUris = Set("https://app.example.test/callback"),
      authMethod = "private_key_jwt",
      jwks = Some(signer.jwks),
      dpopBoundAccessTokens = true,
      accessTokenTtl = 3600,
      requirePushedAuthorizationRequests = true,
    )

  def spec = suite("Tenant security profile")(
    test("a new tenant starts on FAPI 2.0") {
      withTenant: (central, tenantId) =>
        profileOf(central, tenantId).map(profile => assertTrue(profile.contains("fapi2")))
    },
    test("a FAPI 2.0 tenant refuses a client_secret client with a bearer token and no PAR, naming each") {
      withTenant: (central, tenantId) =>
        for
          id <- CentralApi.id("e2e-client")
          refused <- central.post(clients, Fixtures.client(id, tenantId = tenantId, redirectUris = Set("https://app.example.test/cb")))
        yield assertTrue(
          refused.status == Status.BadRequest,
          refused.body.contains("not client_secret"),
          refused.body.contains("sender-constrained"),
          refused.body.contains("pushed authorization"),
        )
    },
    test("a FAPI 2.0 tenant refuses a public client") {
      withTenant: (central, tenantId) =>
        for
          id <- CentralApi.id("e2e-client")
          refused <- central.post(
            clients,
            Fixtures.client(id, tenantId = tenantId, authMethod = "none", redirectUris = Set("https://app.example.test/cb")),
          )
        yield assertTrue(refused.status == Status.BadRequest, refused.body.contains("not none"))
    },
    test("a FAPI 2.0 tenant refuses a web client's private-use scheme redirect URI") {
      withTenant: (central, tenantId) =>
        for
          signer <- AssertionSigner.make
          id <- CentralApi.id("e2e-client")
          refused <- central.post(
            clients,
            Json.Obj(
              conformantClient(id, tenantId, signer).fields.filterNot(_._1 == "redirectUris") :+
                ("redirectUris" -> Json.Arr(Json.Str("com.example.app://callback"))),
            ),
          )
        yield assertTrue(refused.status == Status.BadRequest, refused.body.contains("https redirect URIs"))
    },
    test("a FAPI 2.0 tenant registers a private_key_jwt client with DPoP-bound tokens behind PAR") {
      withTenant: (central, tenantId) =>
        for
          signer <- AssertionSigner.make
          id <- CentralApi.id("e2e-client")
          accepted <- central.post(clients, conformantClient(id, tenantId, signer))
          _ <- central.delete(clients, "clientId" -> id).ignore
        yield assertTrue(accepted.status == Status.Created)
    },
    test("a FAPI 2.0 tenant refuses a patch that would take a client out of the profile") {
      withTenant: (central, tenantId) =>
        for
          signer <- AssertionSigner.make
          id <- CentralApi.id("e2e-client")
          _ <- central.post(clients, conformantClient(id, tenantId, signer))
          _ <- eventually(central.get(clients, "tenantId" -> tenantId).flatMap(_.items("clients")))(_.exists(_.str("id").contains(id)))
          refused <- central.put(clients, Fixtures.clientUpdate(id, "requirePushedAuthorizationRequests" -> Json.Bool(false)))
          accepted <- central.put(clients, Fixtures.clientUpdate(id, "accessTokenTtl" -> Json.Num(7200)))
          _ <- central.delete(clients, "clientId" -> id).ignore
        yield assertTrue(
          refused.status == Status.BadRequest,
          refused.body.contains("pushed authorization"),
          accepted.status == Status.NoContent,
        )
    },
    test("switching to FAPI 2.0 is refused with every violating client, and applied once none is left") {
      withTenant: (central, tenantId) =>
        for
          _ <- SecurityProfiles.ensureStandard(central, tenantId)
          _ <- eventually(profileOf(central, tenantId))(_.contains("standard"))
          secretClient <- CentralApi.id("e2e-secret")
          publicClient <- CentralApi.id("e2e-public")
          _ <- central.post(clients, Fixtures.client(secretClient, tenantId = tenantId))
          _ <- central.post(clients, Fixtures.client(publicClient, tenantId = tenantId, authMethod = "none"))
          refused <- SecurityProfiles.set(central, tenantId, "fapi2")
          body <- refused.obj
          violators = body.objs("violations").flatMap(_.str("clientId")).toSet
          stillStandard <- profileOf(central, tenantId)
          _ <- central.delete(clients, "clientId" -> secretClient)
          _ <- central.delete(clients, "clientId" -> publicClient)
          applied <- eventually(SecurityProfiles.set(central, tenantId, "fapi2"))(_.status == Status.NoContent)
          nowFapi2 <- eventually(profileOf(central, tenantId))(_.contains("fapi2"))
        yield assertTrue(
          refused.status == Status.Conflict,
          body.str("error").contains("security_profile_violations"),
          violators == Set(secretClient, publicClient),
          body.objs("violations").forall(_.strings("reasons").nonEmpty),
          stillStandard.contains("standard"),
          applied.status == Status.NoContent,
          nowFapi2.contains("fapi2"),
        )
    },
  )
