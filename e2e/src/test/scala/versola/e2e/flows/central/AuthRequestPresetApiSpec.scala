package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** Authorization request presets on central's admin API.
  *
  * A preset is a named, server-side authorization request: the edge's `/login/{presetId}` uses
  * one instead of letting the browser choose a redirect URI or a scope. That only holds if
  * central refuses to store a preset the client is not allowed to make, which is what most of
  * these check.
  */
object AuthRequestPresetApiSpec extends CentralApiSpec:

  private val path = "/configuration/auth-request-presets"
  private val clients = "/configuration/clients"

  /** Registers a client the presets can belong to, and takes both it and its presets away
    * afterwards — a preset outlives its client in the database otherwise, and its id stays
    * claimed for every future run.
    *
    * Waits for the client to be readable before handing it over: a preset is validated against
    * its client's redirect URIs, so saving one against a client central has not cached yet is
    * refused as if the client did not exist.
    */
  private def withClient[A](central: CentralApi, redirectUris: Set[String], scopes: Set[String])(
      use: String => Task[A],
  ): Task[A] =
    for
      clientId <- CentralApi.id("e2e-preset-client")
      _ <- central.post(clients, Fixtures.client(clientId, redirectUris = redirectUris, allowedScopes = scopes))
      _ <- eventually(
        central.get(clients, "tenantId" -> Fixtures.defaultTenant).flatMap(_.items("clients")),
      )(_.exists(_.str("id").contains(clientId)))
      result <- use(clientId).ensuring(
        (central.post(path, Fixtures.presets(clientId)) *> central.delete(clients, "clientId" -> clientId)).ignore,
      )
    yield result

  /** The client's presets as central reports them once the write has landed. `expect` is what
    * the test is waiting for: without it a read taken straight after a save can still answer
    * the set from before.
    */
  private def presetsOf(
      central: CentralApi,
      clientId: String,
      expect: Chunk[Json.Obj] => Boolean = _ => true,
  ): Task[Chunk[Json.Obj]] =
    eventually(central.get(path, "clientId" -> clientId).flatMap(_.objects))(expect)

  private val appUri = "http://localhost:3000"
  private val altUri = "https://app.test/callback"

  def spec = suite("Central API: authorization request presets")(
    test("a saved preset is read back for its client") {
      for
        central <- api
        presets <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            saved <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri)))
            listed <- presetsOf(central, clientId, _.exists(_.str("id").contains(presetId)))
          yield (saved, presetId, listed)
        }
        (saved, presetId, listed) = presets
      yield assertTrue(saved.status == Status.NoContent) &&
        assertTrue(listed.exists(_.str("id").contains(presetId)))
    },
    test("a preset reads back with the authorization request it describes") {
      for
        central <- api
        record <- withClient(central, Set(appUri), Set("openid", "email")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(
              path,
              Fixtures.presets(
                clientId,
                Fixtures.preset(
                  presetId,
                  redirectUri = appUri,
                  postLoginRedirectUri = "http://localhost:3000/home",
                  scope = Set("openid", "email"),
                  description = "console login",
                ),
              ),
            )
            listed <- presetsOf(central, clientId, _.exists(_.str("id").contains(presetId)))
          yield listed.find(_.str("id").contains(presetId))
        }
      yield assertTrue(record.flatMap(_.str("redirectUri")).contains(appUri)) &&
        assertTrue(record.flatMap(_.str("postLoginRedirectUri")).contains("http://localhost:3000/home")) &&
        assertTrue(record.map(_.strings("scope")).contains(Set("openid", "email"))) &&
        assertTrue(record.flatMap(_.str("description")).contains("console login"))
    },
    test("a preset names the client it belongs to") {
      for
        central <- api
        outcome <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri)))
            listed <- presetsOf(central, clientId, _.exists(_.str("id").contains(presetId)))
          yield (clientId, listed)
        }
        (clientId, listed) = outcome
      yield assertTrue(listed.forall(_.str("clientId").contains(clientId)))
        .label("the edge resolves a preset to a client, so the link has to be stored, not inferred")
    },
    test("the optional members of a preset survive the round trip") {
      for
        central <- api
        record <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(
              path,
              Fixtures.presets(
                clientId,
                Fixtures.preset(
                  presetId,
                  redirectUri = appUri,
                  postLogoutRedirectUri = Some("http://localhost:3000/bye"),
                  uiLocales = Some(List("en", "ru")),
                  customParameters = Map("tenant_hint" -> List("acme")),
                  cookieDomain = Some("localhost"),
                  cookiePath = Some("/app"),
                ),
              ),
            )
            listed <- presetsOf(central, clientId, _.exists(_.str("id").contains(presetId)))
          yield listed.find(_.str("id").contains(presetId))
        }
      yield assertTrue(record.flatMap(_.str("postLogoutRedirectUri")).contains("http://localhost:3000/bye")) &&
        assertTrue(record.map(_.strings("uiLocales")).contains(Set("en", "ru"))) &&
        assertTrue(record.flatMap(_.str("cookieDomain")).contains("localhost")) &&
        assertTrue(record.flatMap(_.str("cookiePath")).contains("/app")) &&
        assertTrue(record.flatMap(_.obj("customParameters")).map(_.strings("tenant_hint")).contains(Set("acme")))
          .label("a custom parameter the edge forwards verbatim must not be dropped in storage")
    },
    test("the hybrid response type is accepted") {
      for
        central <- api
        record <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(
              path,
              Fixtures.presets(
                clientId,
                Fixtures.preset(presetId, redirectUri = appUri, responseType = "code id_token"),
              ),
            )
            listed <- presetsOf(central, clientId, _.exists(_.str("id").contains(presetId)))
          yield listed.find(_.str("id").contains(presetId))
        }
      yield assertTrue(record.flatMap(_.str("responseType")).contains("code id_token"))
    },
    test("a response type outside the supported set is refused") {
      for
        central <- api
        rejected <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          CentralApi.id("e2e-preset").flatMap { presetId =>
            central.post(
              path,
              Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri, responseType = "token")),
            )
          }
        }
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("response_type"))
          .label("the implicit flow is not implemented; storing it would fail at authorization time")
    },
    test("saving replaces the client's whole set of presets") {
      for
        central <- api
        outcome <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            first <- CentralApi.id("e2e-preset")
            second <- CentralApi.id("e2e-preset")
            _ <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(first, redirectUri = appUri)))
            _ <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(second, redirectUri = appUri)))
            listed <- presetsOf(central, clientId, _.exists(_.str("id").contains(second)))
          yield (first, second, listed)
        }
        (first, second, listed) = outcome
      yield assertTrue(listed.exists(_.str("id").contains(second))) &&
        assertTrue(!listed.exists(_.str("id").contains(first)))
          .label("this endpoint is a desired-state write; leaving the old preset behind would resurrect a retired login")
    },
    test("several presets can be stored for one client at once") {
      for
        central <- api
        listed <- withClient(central, Set(appUri, altUri), Set("openid")) { clientId =>
          for
            first <- CentralApi.id("e2e-preset")
            second <- CentralApi.id("e2e-preset")
            _ <- central.post(
              path,
              Fixtures.presets(
                clientId,
                Fixtures.preset(first, redirectUri = appUri),
                Fixtures.preset(second, redirectUri = altUri),
              ),
            )
            listed <- presetsOf(central, clientId, _.size == 2)
          yield listed
        }
      yield assertTrue(listed.size == 2) &&
        assertTrue(listed.flatMap(_.str("redirectUri")).toSet == Set(appUri, altUri))
    },
    test("saving an empty set clears the client's presets") {
      for
        central <- api
        outcome <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri)))
            cleared <- central.post(path, Fixtures.presets(clientId))
            listed <- presetsOf(central, clientId, _.isEmpty)
          yield (cleared, listed)
        }
        (cleared, listed) = outcome
      yield assertTrue(cleared.status == Status.NoContent) &&
        assertTrue(listed.isEmpty)
          .label("with no delete endpoint, an empty save is the only way to retire a login")
    },
    test("a redirect URI the client has not registered is refused") {
      for
        central <- api
        rejected <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          CentralApi.id("e2e-preset").flatMap { presetId =>
            central.post(
              path,
              Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = "https://attacker.test/steal")),
            )
          }
        }
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("a preset the OP would reject at authorization time must not be storable")
    },
    test("a rejected preset leaves the existing set untouched") {
      for
        central <- api
        outcome <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri)))
            other <- CentralApi.id("e2e-preset")
            _ <- central.post(
              path,
              Fixtures.presets(clientId, Fixtures.preset(other, redirectUri = "https://attacker.test/steal")),
            )
            listed <- presetsOf(central, clientId, _.exists(_.str("id").contains(presetId)))
          yield (presetId, listed)
        }
        (presetId, listed) = outcome
      yield assertTrue(listed.exists(_.str("id").contains(presetId)))
        .label("validation runs before the replace, so a failed save must not have cleared anything")
    },
    test("a scope the client is not allowed is refused") {
      for
        central <- api
        rejected <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          CentralApi.id("e2e-preset").flatMap { presetId =>
            central.post(
              path,
              Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri, scope = Set("profile"))),
            )
          }
        }
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("the preset is the request; a scope outside allowedScopes could never be granted")
    },
    test("a scope set the client fully allows is accepted") {
      for
        central <- api
        saved <- withClient(central, Set(appUri), Set("openid", "email", "offline_access")) { clientId =>
          CentralApi.id("e2e-preset").flatMap { presetId =>
            central.post(
              path,
              Fixtures.presets(
                clientId,
                Fixtures.preset(presetId, redirectUri = appUri, scope = Set("openid", "offline_access")),
              ),
            )
          }
        }
      yield assertTrue(saved.status == Status.NoContent)
        .label("a proper subset is legitimate: a preset need not ask for everything the client may")
    },
    test("presets for a client that does not exist are refused") {
      for
        central <- api
        clientId <- CentralApi.id("e2e-absent")
        presetId <- CentralApi.id("e2e-preset")
        rejected <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri)))
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("without a client there are no redirect URIs to validate the preset against")
    },
    test("two presets sharing an id in one request are refused") {
      for
        central <- api
        rejected <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          CentralApi.id("e2e-preset").flatMap { presetId =>
            central.post(
              path,
              Fixtures.presets(
                clientId,
                Fixtures.preset(presetId, redirectUri = appUri, description = "first"),
                Fixtures.preset(presetId, redirectUri = appUri, description = "second"),
              ),
            )
          }
        }
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("already used"))
    },
    test("a preset id already claimed by another client is refused") {
      for
        central <- api
        presetId <- CentralApi.id("e2e-preset")
        rejected <- withClient(central, Set(appUri), Set("openid")) { owner =>
          central.post(path, Fixtures.presets(owner, Fixtures.preset(presetId, redirectUri = appUri))) *>
            withClient(central, Set(appUri), Set("openid")) { other =>
              central.post(path, Fixtures.presets(other, Fixtures.preset(presetId, redirectUri = appUri)))
            }
        }
      yield assertTrue(rejected.status == Status.BadRequest)
        .label("`/login/{presetId}` has no client in its path, so a preset id must be globally unique")
    },
    test("re-saving a client's own preset id is not a conflict with itself") {
      for
        central <- api
        outcome <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(path, Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri)))
            again <- central.post(
              path,
              Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri, description = "renamed")),
            )
            listed <- presetsOf(central, clientId, _.exists(_.str("description").contains("renamed")))
          yield (again, listed.find(_.str("id").contains(presetId)))
        }
        (again, record) = outcome
      yield assertTrue(again.status == Status.NoContent) &&
        assertTrue(record.flatMap(_.str("description")).contains("renamed"))
          .label("editing a preset in place is the normal case and must not read as a duplicate")
    },
    test("the listing is per client and demands to be told which") {
      for
        central <- api
        rejected <- central.get(path)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("clientId"))
    },
    test("a client with no presets lists an empty collection") {
      for
        central <- api
        listed <- withClient(central, Set(appUri), Set("openid"))(presetsOf(central, _))
      yield assertTrue(listed.isEmpty)
    },
    test("a client's listing does not include another client's presets") {
      for
        central <- api
        outcome <- withClient(central, Set(appUri), Set("openid")) { owner =>
          for
            presetId <- CentralApi.id("e2e-preset")
            _ <- central.post(path, Fixtures.presets(owner, Fixtures.preset(presetId, redirectUri = appUri)))
            otherView <- withClient(central, Set(appUri), Set("openid"))(presetsOf(central, _))
          yield (presetId, otherView)
        }
        (presetId, otherView) = outcome
      yield assertTrue(!otherView.exists(_.str("id").contains(presetId)))
    },
    test("a body that is not JSON is refused") {
      for
        central <- api
        rejected <- central.raw(Method.POST, path, "presets")
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a preset missing a required member is refused") {
      for
        central <- api
        rejected <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          central.post(
            path,
            Json.Obj(
              "clientId" -> Json.Str(clientId),
              "presets" -> Json.Arr(Chunk(Json.Obj("id" -> Json.Str("incomplete")))),
            ),
          )
        }
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an anonymous caller cannot read a client's presets") {
      for
        central <- api
        listed <- central.anonymous.get(path, "clientId" -> "irrelevant")
      yield assertTrue(listed.status == Status.Unauthorized)
        .label("a preset lists the redirect URIs and scopes of a client; that is not public")
    },
    test("an anonymous caller cannot save presets") {
      for
        central <- api
        outcome <- withClient(central, Set(appUri), Set("openid")) { clientId =>
          for
            presetId <- CentralApi.id("e2e-preset")
            rejected <- central.anonymous
              .post(path, Fixtures.presets(clientId, Fixtures.preset(presetId, redirectUri = appUri)))
            listed <- presetsOf(central, clientId)
          yield (rejected, listed)
        }
        (rejected, listed) = outcome
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(listed.isEmpty)
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
