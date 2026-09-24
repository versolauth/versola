package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** OAuth client registration and configuration on central's admin API.
  *
  * The client record is what auth reads to decide whether an authorization request is even
  * admissible — its redirect URIs, its allowed scopes, its token lifetimes. A client stored
  * differently from how it was submitted is a security-relevant defect, so most of these read
  * the record back rather than trusting the write's status code.
  */
object ClientApiSpec extends CentralApiSpec:

  private val path = "/configuration/clients"

  /** The client's own record out of a listing, so a test states which member it is checking
    * rather than indexing into a response by position.
    */
  private def find(central: CentralApi, clientId: String): Task[Option[Json.Obj]] =
    central.get(path, "tenantId" -> Fixtures.defaultTenant)
      .flatMap(_.items("clients"))
      .map(_.find(_.str("id").contains(clientId)))

  /** The client as central reports it once the write has landed. `expect` is what the test is
    * waiting for: without it a read taken straight after an update can still answer the
    * version from before.
    */
  private def read(
      central: CentralApi,
      clientId: String,
      expect: Json.Obj => Boolean = _ => true,
  ): Task[Option[Json.Obj]] =
    eventually(find(central, clientId))(_.exists(expect))

  /** Waits for the client to be gone, for the tests that assert an absence. */
  private def gone(central: CentralApi, clientId: String): Task[Option[Json.Obj]] =
    eventually(find(central, clientId))(_.isEmpty)

  /** Registers a client, runs the test body against it, and deletes it either way. */
  private def withClient[A](central: CentralApi, body: Json.Obj)(use: String => Task[A]): Task[A] =
    val clientId = body.str("id").getOrElse(throw IllegalArgumentException("fixture has no id"))
    ZIO.acquireReleaseWith(central.post(path, body))(_ => central.delete(path, "clientId" -> clientId).ignore)(_ =>
      use(clientId),
    )

  def spec = suite("Central API: clients")(
    test("registration answers 201 with the generated secret") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        created <- central.post(path, Fixtures.client(id))
        secret <- created.stringAt("secret")
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(created.status == Status.Created) &&
        assertTrue(secret.nonEmpty) &&
        assertTrue(!secret.contains("=") && !secret.contains("+") && !secret.contains("/"))
          .label("the secret is base64url, so it is safe in a Basic header without re-encoding")
    },
    test("two clients registered back to back get different secrets") {
      for
        central <- api
        first <- CentralApi.id("e2e-client")
        second <- CentralApi.id("e2e-client")
        one <- central.post(path, Fixtures.client(first)).flatMap(_.stringAt("secret"))
        two <- central.post(path, Fixtures.client(second)).flatMap(_.stringAt("secret"))
        _ <- central.delete(path, "clientId" -> first)
        _ <- central.delete(path, "clientId" -> second)
      yield assertTrue(one != two).label("a shared secret would make one client able to impersonate the other")
    },
    test("a registered client reads back with the identity it was given") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        record <- withClient(central, Fixtures.client(id, name = "Checkout")) { _ =>
          read(central, id)
        }
      yield assertTrue(record.flatMap(_.obj("clientName")).flatMap(_.str("en")).contains("Checkout"))
    },
    test("a registered client reads back with its redirect URIs and scopes") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(
          id,
          redirectUris = Set("http://localhost:3000", "https://app.test/callback"),
          allowedScopes = Set("openid", "email"),
        )
        record <- withClient(central, body)(_ => read(central, id))
      yield assertTrue(record.map(_.strings("redirectUris")).contains(Set("http://localhost:3000", "https://app.test/callback"))) &&
        assertTrue(record.map(_.strings("scope")).contains(Set("openid", "email")))
          .label("auth refuses any scope not on this list, so the stored set has to be exact")
    },
    test("a registered client reads back with its token lifetimes") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, accessTokenTtl = 120, refreshTokenTtl = Some(3600))
        record <- withClient(central, body)(_ => read(central, id))
      yield assertTrue(record.flatMap(_.int("accessTokenTtl")).contains(120)) &&
        assertTrue(record.flatMap(_.int("refreshTokenTtl")).contains(3600))
    },
    test("an omitted refresh token lifetime falls back to ninety days") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        record <- withClient(central, Fixtures.client(id))(_ => read(central, id))
      yield assertTrue(record.flatMap(_.int("refreshTokenTtl")).contains(7776000))
        .label("clients registered without a refresh lifetime must still get a usable default")
    },
    test("a new client reports no secret rotation in progress") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        record <- withClient(central, Fixtures.client(id))(_ => read(central, id))
      yield assertTrue(record.flatMap(_.bool("secretRotation")).contains(false))
    },
    test("consent links are stored when they are absolute HTTPS URLs") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(
          id,
          logoUri = Some("https://app.test/logo.png"),
          policyUri = Some("https://app.test/privacy"),
          tosUri = Some("https://app.test/terms"),
        )
        record <- withClient(central, body)(_ => read(central, id))
      yield assertTrue(record.flatMap(_.str("logoUri")).contains("https://app.test/logo.png")) &&
        assertTrue(record.flatMap(_.str("policyUri")).contains("https://app.test/privacy")) &&
        assertTrue(record.flatMap(_.str("tosUri")).contains("https://app.test/terms"))
    },
    test("a plain HTTP consent link is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Fixtures.client(id, logoUri = Some("http://app.test/logo.png")))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("logoUri"))
          .label("the error has to name the offending member, or the operator cannot fix it")
    },
    test("a relative consent link is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Fixtures.client(id, policyUri = Some("/privacy")))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("the browser loads this from the consent screen, so it must be absolute")
    },
    test("an HTTPS front-channel logout URI is stored") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(
          id,
          frontChannelLogoutUri = Some("https://app.test/logout"),
          frontChannelLogoutSessionRequired = true,
        )
        record <- withClient(central, body)(_ => read(central, id))
      yield assertTrue(record.flatMap(_.str("frontChannelLogoutUri")).contains("https://app.test/logout")) &&
        assertTrue(record.flatMap(_.bool("frontChannelLogoutSessionRequired")).contains(true))
    },
    test("a front-channel logout URI on http://localhost is allowed for local development") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, frontChannelLogoutUri = Some("http://localhost:3000/logout"))
        record <- withClient(central, body)(_ => read(central, id))
      yield assertTrue(record.flatMap(_.str("frontChannelLogoutUri")).contains("http://localhost:3000/logout"))
        .label("developers have no TLS locally; refusing this would push them off the feature")
    },
    test("a front-channel logout URI on a non-local plain HTTP origin is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Fixtures.client(id, frontChannelLogoutUri = Some("http://app.test/logout")))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("a logout notification over plain HTTP leaks the session id it carries")
    },
    test("a logout URI carrying a fragment is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Fixtures.client(id, frontChannelLogoutUri = Some("https://app.test/l#done")))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("a fragment never reaches the server, so it cannot be part of a callback URI")
    },
    test("configuring both logout channels at once is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(
          path,
          Fixtures.client(
            id,
            frontChannelLogoutUri = Some("https://app.test/fc"),
            backChannelLogoutUri = Some("https://app.test/bc"),
          ),
        )
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("one of"))
          .label("the two channels are alternatives; accepting both leaves the OP no rule to pick by")
    },
    test("a registered client reads back its mutual-TLS auth configuration") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, authMethod = "tls_client_auth", mtlsAuth = Some(Fixtures.mutualTlsAuth("subject_dn", "CN=e2e-client,O=Example")))
        record <- withClient(central, body)(_ => read(central, id))
        mtlsAuth = record.flatMap(_.obj("mtlsAuth"))
      yield assertTrue(mtlsAuth.flatMap(_.str("subjectType")).contains("subject_dn")) &&
        assertTrue(mtlsAuth.flatMap(_.str("subjectValue")).contains("CN=e2e-client,O=Example"))
    },
    test("a registered mutual-TLS subject value is stored trimmed") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, authMethod = "tls_client_auth", mtlsAuth = Some(Fixtures.mutualTlsAuth("san_dns", "  client.example.com  ")))
        record <- withClient(central, body)(_ => read(central, id))
      yield assertTrue(record.flatMap(_.obj("mtlsAuth")).flatMap(_.str("subjectValue")).contains("client.example.com"))
        .label("RFC 8705 \u00a72.1.2 compares this literally, so pasted whitespace must not survive registration")
    },
    test("a client not configured for mutual TLS reads back no mtlsAuth") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        record <- withClient(central, Fixtures.client(id))(_ => read(central, id))
      yield assertTrue(record.exists(!_.has("mtlsAuth")) || record.exists(_.isNull("mtlsAuth")))
    },
    test("certificateBoundAccessTokens is stored as registered") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, certificateBoundAccessTokens = true)
        record <- withClient(central, body)(_ => read(central, id, _.bool("certificateBoundAccessTokens").contains(true)))
      yield assertTrue(record.flatMap(_.bool("certificateBoundAccessTokens")).contains(true))
        .label("RFC 8705 \u00a73.4 lets a client bind its tokens without authenticating by certificate")
    },
    test("a self-signed mutual-TLS client reads back the method and the keys it matches against") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(
          id,
          authMethod = "self_signed_tls_client_auth",
          mtlsAuth = Some(Fixtures.selfSignedTlsClientAuth),
          jwks = Some(Fixtures.ClientCertificates.client.jwks),
        )
        record <- withClient(central, body)(_ => read(central, id))
        mtlsAuth = record.flatMap(_.obj("mtlsAuth"))
      yield assertTrue(mtlsAuth.flatMap(_.str("type")).contains("self_signed_tls_client_auth")) &&
        assertTrue(record.flatMap(_.obj("jwks")).isDefined)
          .label("RFC 8705 §2.2 registers keys rather than a subject, and needs them stored together")
    },
    test("a self-signed mutual-TLS client with no keys to match against is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Fixtures.client(id, authMethod = "self_signed_tls_client_auth", mtlsAuth = Some(Fixtures.selfSignedTlsClientAuth)))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("§2.2 has nothing to compare a certificate against without jwks")
    },
    test("mtlsAuth is refused for a tenant whose proxy terminates no mutual TLS") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        id <- CentralApi.id("e2e-client")
        // A freshly created tenant has no challenge settings naming a certificate header, so
        // auth would never look for this client's certificate (RFC 8705 §6.5).
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        rejected <- central.post(
          path,
          Fixtures.client(
            id,
            tenantId = tenantId,
            authMethod = "tls_client_auth",
            mtlsAuth = Some(Fixtures.mutualTlsAuth("subject_dn", "CN=e2e-client,O=Example")),
          ),
        )
        accepted <- central.post(path, Fixtures.client(id, tenantId = tenantId))
        _ <- central.delete(path, "clientId" -> id).ignore
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId).ignore
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("a client whose certificate nothing looks for can never authenticate") &&
        assertTrue(accepted.status == Status.Created)
          .label("the same tenant still registers a client that asks for no certificate")
    },
    test("an unrecognised mtlsAuth subjectType is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(
          path,
          Fixtures.client(id, authMethod = "tls_client_auth", mtlsAuth = Some(Fixtures.mutualTlsAuth("not_a_subject_type", "CN=x"))),
        )
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an update sets mtlsAuth on a client that had none") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        outcome <- withClient(central, Fixtures.client(id)) { clientId =>
          central.put(
            path,
            Fixtures.clientUpdate(
              clientId,
              "mtlsAuth" -> Fixtures.mutualTlsAuth("subject_dn", "CN=updated,O=Example"),
            ),
          ).zip(read(central, id, _.obj("mtlsAuth").isDefined))
        }
        (updated, record) = outcome
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.flatMap(_.obj("mtlsAuth")).flatMap(_.str("subjectValue")).contains("CN=updated,O=Example"))
    },
    test("an update clears mtlsAuth with an explicit null") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, authMethod = "tls_client_auth", mtlsAuth = Some(Fixtures.mutualTlsAuth("subject_dn", "CN=e2e-client,O=Example")))
        outcome <- withClient(central, body) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "mtlsAuth" -> Json.Null))
            .zip(read(central, id, r => !r.has("mtlsAuth") || r.isNull("mtlsAuth")))
        }
        (updated, record) = outcome
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.exists(!_.has("mtlsAuth")) || record.exists(_.isNull("mtlsAuth")))
          .label("a `null` patch is how this API spells deleting an optional member, same as consentFlow")
    },
    test("an update leaving mtlsAuth unmentioned does not disturb it") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, authMethod = "tls_client_auth", mtlsAuth = Some(Fixtures.mutualTlsAuth("subject_dn", "CN=untouched,O=Example")))
        record <- withClient(central, body) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "accessTokenTtl" -> Json.Num(60)))
            *> read(central, id, _.int("accessTokenTtl").contains(60))
        }
      yield assertTrue(record.flatMap(_.obj("mtlsAuth")).flatMap(_.str("subjectValue")).contains("CN=untouched,O=Example"))
    },
    test("an update changes certificateBoundAccessTokens on its own") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        outcome <- withClient(central, Fixtures.client(id)) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "certificateBoundAccessTokens" -> Json.Bool(true)))
            .zip(read(central, id, _.bool("certificateBoundAccessTokens").contains(true)))
        }
        (updated, record) = outcome
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.flatMap(_.bool("certificateBoundAccessTokens")).contains(true))
    },
    test("a back-channel logout URI alone is accepted") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        body = Fixtures.client(id, backChannelLogoutUri = Some("https://app.test/backchannel"))
        record <- withClient(central, body)(_ => read(central, id))
      yield assertTrue(record.flatMap(_.str("backChannelLogoutUri")).contains("https://app.test/backchannel"))
    },
    test("a second registration under the same id is refused as a conflict") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        first <- central.post(path, Fixtures.client(id))
        second <- central.post(path, Fixtures.client(id, name = "impostor"))
        record <- read(central, id, _.obj("clientName").exists(_.str("en").isDefined))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(first.status == Status.Created) &&
        assertTrue(second.status == Status.Conflict) &&
        assertTrue(record.flatMap(_.obj("clientName")).flatMap(_.str("en")).contains("e2e client"))
          .label("a silently overwritten client would hand the id's secret to a different app")
    },
    test("an id that breaks the documented pattern is refused") {
      for
        central <- api
        rejected <- central.post(path, Fixtures.client("Not A Client"))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("Client ID"))
    },
    test("a redirect URI that is not a URI is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Fixtures.client(id, redirectUris = Set("not a uri")))
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("redirectUris"))
    },
    test("a permission outside the documented alphabet is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Fixtures.client(id, permissions = Set("Not A Permission")))
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("permissions are matched literally against endpoint grants, so the alphabet is fixed")
    },
    test("a body missing a required member is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.post(path, Json.Obj("tenantId" -> Json.Str(Fixtures.defaultTenant), "id" -> Json.Str(id)))
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "{")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("the listing is scoped to one tenant and demands to be told which") {
      for
        central <- api
        rejected <- central.get(path)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("tenantId"))
          .label("listing every tenant's clients at once would leak across tenants")
    },
    test("a tenant with no clients lists an empty collection rather than failing") {
      for
        central <- api
        unknown <- CentralApi.id("e2e-absent")
        listed <- central.get(path, "tenantId" -> unknown)
        clients <- listed.items("clients")
      yield assertTrue(listed.status == Status.Ok) && assertTrue(clients.isEmpty)
    },
    test("a client is not listed under a tenant it does not belong to") {
      for
        central <- api
        tenantId <- CentralApi.id("e2e-tenant")
        id <- CentralApi.id("e2e-client")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        _ <- central.post(path, Fixtures.client(id, tenantId = tenantId))
        inOwnTenant <- eventually(central.get(path, "tenantId" -> tenantId).flatMap(_.items("clients")))(
          _.exists(_.str("id").contains(id)),
        )
        inDefault <- gone(central, id)
        _ <- central.delete(path, "clientId" -> id)
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(inOwnTenant.exists(_.str("id").contains(id))) &&
        assertTrue(inDefault.isEmpty).label("tenant isolation is the whole point of the query parameter")
    },
    test("limit caps the number of clients returned") {
      for
        central <- api
        listed <- central.get(path, "tenantId" -> Fixtures.defaultTenant, "limit" -> "1")
        clients <- listed.items("clients")
      yield assertTrue(clients.size <= 1)
        .label("the console pages this listing, so limit has to be honoured server-side")
    },
    test("offset moves the window without overlapping it") {
      for
        central <- api
        idA <- CentralApi.id("e2e-client")
        idB <- CentralApi.id("e2e-client")
        result <- withClient(central, Fixtures.client(idA)) { _ =>
          withClient(central, Fixtures.client(idB)) { _ =>
            for
              _ <- read(central, idA)
              _ <- read(central, idB)
              first <- central.get(path, "tenantId" -> Fixtures.defaultTenant, "offset" -> "0", "limit" -> "1")
                .flatMap(_.items("clients"))
              second <- central.get(path, "tenantId" -> Fixtures.defaultTenant, "offset" -> "1", "limit" -> "1")
                .flatMap(_.items("clients"))
            yield (first, second)
          }
        }
        (first, second) = result
      yield assertTrue(first.size == 1 && second.size == 1) &&
        assertTrue(first.head.str("id") != second.head.str("id"))
          .label("a second page repeating the first would make paging unusable")
    },
    test("an update replaces the client name outright") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        record <- withClient(central, Fixtures.client(id, name = "Before")) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "clientName" -> Fixtures.text("After")))
            *> read(central, id, _.obj("clientName").exists(_.str("en").contains("After")))
        }
        name = record.flatMap(_.obj("clientName"))
      yield assertTrue(name.flatMap(_.str("en")).contains("After")) &&
        assertTrue(name.map(_.fields.size).contains(1))
          .label("clientName is an overwrite, not a merge: stale translations must not survive")
    },
    test("an update adds and removes redirect URIs in one call") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        outcome <- withClient(central, Fixtures.client(id, redirectUris = Set("http://localhost:3000"))) { clientId =>
          central.put(
            path,
            Fixtures.clientUpdate(
              clientId,
              "redirectUris" -> Fixtures.patch(add = Set("https://app.test/cb"), remove = Set("http://localhost:3000")),
            ),
          ).zip(read(central, id, _.strings("redirectUris") == Set("https://app.test/cb")))
        }
        (updated, record) = outcome
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.map(_.strings("redirectUris")).contains(Set("https://app.test/cb")))
          .label("a rollout swaps one URI for another atomically; two calls would leave a gap")
    },
    test("an update adds a scope without disturbing the ones already allowed") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        outcome <- withClient(central, Fixtures.client(id, allowedScopes = Set("openid"))) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "scope" -> Fixtures.patch(add = Set("email"))))
            .zip(read(central, id, _.strings("scope") == Set("openid", "email")))
        }
        (updated, record) = outcome
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.map(_.strings("scope")).contains(Set("openid", "email")))
    },
    test("an update removes a scope") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        outcome <- withClient(central, Fixtures.client(id, allowedScopes = Set("openid", "email"))) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "scope" -> Fixtures.patch(remove = Set("email"))))
            .zip(read(central, id, _.strings("scope") == Set("openid")))
        }
        (updated, record) = outcome
      yield assertTrue(updated.status == Status.NoContent) &&
        assertTrue(record.map(_.strings("scope")).contains(Set("openid")))
    },
    test("removing something the client never had is not an error") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        outcome <- withClient(central, Fixtures.client(id)) { clientId =>
          central.put(
            path,
            Fixtures.clientUpdate(clientId, "redirectUris" -> Fixtures.patch(remove = Set("https://gone.test/cb"))),
          )
        }
      yield assertTrue(outcome.status == Status.NoContent)
        .label("a converged desired-state apply repeats removals; each one has to be idempotent")
    },
    test("an update changes the token lifetimes") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        record <- withClient(central, Fixtures.client(id, accessTokenTtl = 3600)) { clientId =>
          central.put(
            path,
            Fixtures.clientUpdate(
              clientId,
              "accessTokenTtl" -> Json.Num(60),
              "refreshTokenTtl" -> Json.Num(600),
            ),
          ) *> read(central, id, _.int("accessTokenTtl").contains(60))
        }
      yield assertTrue(record.flatMap(_.int("accessTokenTtl")).contains(60)) &&
        assertTrue(record.flatMap(_.int("refreshTokenTtl")).contains(600))
    },
    test("an update leaves members it does not mention alone") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        record <- withClient(central, Fixtures.client(id, name = "Untouched", accessTokenTtl = 300)) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "refreshTokenTtl" -> Json.Num(900)))
            *> read(central, id, _.int("refreshTokenTtl").contains(900))
        }
      yield assertTrue(record.flatMap(_.obj("clientName")).flatMap(_.str("en")).contains("Untouched")) &&
        assertTrue(record.flatMap(_.int("accessTokenTtl")).contains(300))
          .label("an update naming one member must not reset the rest to defaults")
    },
    test("an update refuses to set both logout channels") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- withClient(central, Fixtures.client(id)) { clientId =>
          central.put(
            path,
            Fixtures.clientUpdate(
              clientId,
              "frontChannelLogoutUri" -> Json.Str("https://app.test/fc"),
              "backChannelLogoutUri" -> Json.Str("https://app.test/bc"),
            ),
          )
        }
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an update refuses a logout URI it would not have accepted at registration") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- withClient(central, Fixtures.client(id)) { clientId =>
          central.put(
            path,
            Fixtures.clientUpdate(clientId, "frontChannelLogoutUri" -> Json.Str("http://app.test/logout")),
          )
        }
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("validation that only runs on the create path is validation an update can bypass")
    },
    test("an update refuses a malformed redirect URI") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- withClient(central, Fixtures.client(id)) { clientId =>
          central.put(path, Fixtures.clientUpdate(clientId, "redirectUris" -> Fixtures.patch(add = Set("nope"))))
        }
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an update without the required patch members is refused") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        rejected <- central.put(path, Json.Obj("clientId" -> Json.Str(id)))
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("rotating the secret answers a different secret") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        original <- central.post(path, Fixtures.client(id)).flatMap(_.stringAt("secret"))
        rotated <- central.postEmpty(s"$path/rotate-secret", "clientId" -> id)
        replacement <- rotated.stringAt("secret")
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rotated.status == Status.Ok) &&
        assertTrue(replacement != original).label("a rotation that returns the same secret rotates nothing")
    },
    test("a rotated client reports a rotation in progress") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id))
        _ <- central.postEmpty(s"$path/rotate-secret", "clientId" -> id)
        record <- read(central, id, _.bool("secretRotation").contains(true))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(record.flatMap(_.bool("secretRotation")).contains(true))
        .label("the console shows this to warn that the old secret is still live")
    },
    test("dropping the previous secret ends the rotation") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id))
        _ <- central.postEmpty(s"$path/rotate-secret", "clientId" -> id)
        dropped <- central.delete(s"$path/previous-secret", "clientId" -> id)
        record <- read(central, id, _.bool("secretRotation").contains(false))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(dropped.status == Status.NoContent) &&
        assertTrue(record.flatMap(_.bool("secretRotation")).contains(false))
          .label("until the old secret is dropped it still authenticates, so the state has to be visible")
    },
    test("dropping a previous secret that was never minted is not an error") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id))
        dropped <- central.delete(s"$path/previous-secret", "clientId" -> id)
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(dropped.status == Status.NoContent)
    },
    test("rotating twice leaves only one previous secret to retire") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id))
        first <- central.postEmpty(s"$path/rotate-secret", "clientId" -> id).flatMap(_.stringAt("secret"))
        second <- central.postEmpty(s"$path/rotate-secret", "clientId" -> id).flatMap(_.stringAt("secret"))
        _ <- central.delete(s"$path/previous-secret", "clientId" -> id)
        record <- read(central, id, _.bool("secretRotation").contains(false))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(first != second) &&
        assertTrue(record.flatMap(_.bool("secretRotation")).contains(false))
          .label("only the immediately preceding secret stays valid; older ones are gone for good")
    },
    test("rotating the secret of an unknown client does not bring one into existence") {
      for
        central <- api
        id <- CentralApi.id("e2e-absent")
        _ <- central.postEmpty(s"$path/rotate-secret", "clientId" -> id)
        record <- gone(central, id)
      yield assertTrue(record.isEmpty)
        .label("a rotation must never be a back door for creating an unconfigured client")
    },
    test("rotating without a clientId is refused") {
      for
        central <- api
        rejected <- central.postEmpty(s"$path/rotate-secret")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a deleted client stops being listed") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id))
        deleted <- central.delete(path, "clientId" -> id)
        record <- gone(central, id)
      yield assertTrue(deleted.status == Status.NoContent) && assertTrue(record.isEmpty)
    },
    test("deleting an unknown client is not an error") {
      for
        central <- api
        id <- CentralApi.id("e2e-absent")
        deleted <- central.delete(path, "clientId" -> id)
      yield assertTrue(deleted.status == Status.NoContent)
    },
    test("deleting without a clientId is refused") {
      for
        central <- api
        rejected <- central.delete(path)
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an id can be reused after the client holding it is deleted") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id, name = "First tenant of the id"))
        _ <- central.delete(path, "clientId" -> id)
        again <- central.post(path, Fixtures.client(id, name = "Second"))
        record <- read(central, id, _.obj("clientName").exists(_.str("en").contains("Second")))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(again.status == Status.Created) &&
        assertTrue(record.flatMap(_.obj("clientName")).flatMap(_.str("en")).contains("Second"))
    },
    test("an anonymous caller cannot list clients") {
      for
        central <- api
        listed <- central.anonymous.get(path, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(listed.status == Status.Unauthorized)
    },
    test("an anonymous caller cannot register a client") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        created <- central.anonymous.post(path, Fixtures.client(id))
        record <- gone(central, id)
      yield assertTrue(created.status == Status.Unauthorized) &&
        assertTrue(record.isEmpty).label("a rejected registration must not leave a client behind")
    },
    test("an anonymous caller cannot rotate a secret") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id))
        rejected <- central.anonymous.postEmpty(s"$path/rotate-secret", "clientId" -> id)
        record <- read(central, id, _.bool("secretRotation").contains(false))
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.Unauthorized) &&
        assertTrue(record.flatMap(_.bool("secretRotation")).contains(false))
          .label("an unauthenticated rotation would be a denial of service on a live client")
    },
    test("a caller presenting the wrong secret cannot delete a client") {
      for
        central <- api
        id <- CentralApi.id("e2e-client")
        _ <- central.post(path, Fixtures.client(id))
        rejected <- central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
          .delete(path, "clientId" -> id)
        record <- read(central, id)
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(record.nonEmpty)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
