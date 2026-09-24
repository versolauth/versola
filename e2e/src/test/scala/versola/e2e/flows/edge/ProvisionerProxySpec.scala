package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.ast.Json
import zio.test.*

/** The path `loadgen provision` takes to central: a `client_credentials` token for
  * `resource://central`, then central's own admin API through edge's resource proxy.
  *
  * Only this level can show that the three halves agree. The token has to come back with the
  * internal resource's audience, which depends on central having seeded the provisioner into
  * the `central` resource's audience list; edge has to match the rest-of-path against the
  * endpoint catalog that same bootstrap registered, and authorize a service token against the
  * client's own permissions rather than a user's roles; central has to accept the Basic
  * credential edge substitutes. A unit test can stub any one of those and still pass.
  */
object ProvisionerProxySpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & E2EConfig]:

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & E2EConfig] =
    (E2EConfig.live ++ Client.default) >+> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val config = ZIO.service[E2EConfig]

  /** The provisioner's own credential, exchanged for a token bound to central. */
  private val provisionerToken: ZIO[OAuthClient & E2EConfig, Throwable, String] =
    for
      auth <- ZIO.service[OAuthClient]
      c <- config
      issued <- auth.clientCredentials(
        clientId = c.provisionerClientId,
        clientSecret = c.provisionerSecret,
        resources = Some(List("resource://central")),
      ).success
    yield issued.accessToken

  def spec = suite("Edge: central's admin API as the provisioner reaches it")(
    test("a token for resource://central reads central's configuration through the proxy") {
      for
        token <- provisionerToken
        edgeApi <- ZIO.service[EdgeApi]
        listed <- edgeApi.proxy(Method.GET, "central", "/configuration/clients", EdgeAuth.Bearer(token), query = List("tenantId" -> "default"))
        body <- listed.obj
      yield assertTrue(listed.status == Status.Ok) &&
        assertTrue(body.get("clients").isDefined)
          .label("the body must be central's own listing, not edge's")
    },
    test("it writes, reads back and deletes a resource the way provision does") {
      for
        token <- provisionerToken
        edgeApi <- ZIO.service[EdgeApi]
        auth = EdgeAuth.Bearer(token)
        resourceId <- CentralApi.id("e2e-provisioned")
        created <- edgeApi.proxy(
          Method.POST,
          "central",
          "/configuration/resources",
          auth,
          body = Some(Fixtures.resource(resourceId, s"https://$resourceId.example.test")),
        )
        listed <- edgeApi.proxy(Method.GET, "central", "/configuration/resources", auth, query = List("tenantId" -> "default"))
        body <- listed.obj
        removed <- edgeApi.proxy(Method.DELETE, "central", "/configuration/resources", auth, query = List("resourceId" -> resourceId))
        present = resources(body).contains(resourceId)
      yield assertTrue(created.status == Status.Created, removed.status == Status.NoContent) &&
        assertTrue(present).label("a resource written through the proxy must be readable through it")
    },
    // The two endpoints that were not in the catalog at all until `provision` needed them, and
    // that are registered outside production only.
    test("it triggers central's configuration sync and outbox flush") {
      for
        token <- provisionerToken
        edgeApi <- ZIO.service[EdgeApi]
        auth = EdgeAuth.Bearer(token)
        synced <- edgeApi.proxy(Method.POST, "central", "/service/configuration/sync", auth)
        flushed <- edgeApi.proxy(Method.POST, "central", "/service/users/outbox/flush", auth)
      yield assertTrue(synced.status.isSuccess, flushed.status.isSuccess)
    },
    // `users:read` is deliberately absent from the provisioner's permissions: a campaign writes
    // no users, and the resource secret this client replaces could read every one of them.
    test("it cannot read users, which its permissions do not cover") {
      for
        token <- provisionerToken
        edgeApi <- ZIO.service[EdgeApi]
        denied <- edgeApi.proxy(Method.GET, "central", "/users", EdgeAuth.Bearer(token), query = List("tenantId" -> "default"))
      yield assertTrue(denied.status == Status.Forbidden)
    },
    test("a token for a different audience is refused by the proxy") {
      for
        auth <- ZIO.service[OAuthClient]
        c <- config
        edgeApi <- ZIO.service[EdgeApi]
        issued <- auth.clientCredentials(clientId = c.provisionerClientId, clientSecret = c.provisionerSecret).success
        refused <- edgeApi.proxy(
          Method.GET,
          "central",
          "/configuration/clients",
          EdgeAuth.Bearer(issued.accessToken),
          query = List("tenantId" -> "default"),
        )
      yield assertTrue(refused.status == Status.Forbidden)
    },
  )

  private def resources(body: Json.Obj): List[String] =
    body.get("resources").toList.flatMap:
      case Json.Arr(entries) => entries.toList.flatMap(_.asObject).flatMap(_.get("resourceId")).collect { case Json.Str(id) => id }
      case _ => Nil
