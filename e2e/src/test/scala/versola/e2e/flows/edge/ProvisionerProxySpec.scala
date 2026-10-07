package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.*
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

  /** The provisioner's own credential, exchanged for a DPoP-bound token for central: an RFC
    * 7523 assertion signed with the key bootstrap registered, which is how `loadgen provision`
    * authenticates against a default-profile deployment. */
  private val provisionerToken: ZIO[OAuthClient & E2EConfig, Throwable, ProvisionerSession] =
    for
      auth <- ZIO.service[OAuthClient]
      c <- config
      session <- ProvisionerCredential.session(auth, c, List("resource://central"))
    yield session

  def spec = suite("Edge: central's admin API as the provisioner reaches it")(
    test("a token for resource://central reads central's configuration through the proxy") {
      for
        token <- provisionerToken
        edgeApi <- ZIO.service[EdgeApi]
        listed <- edgeApi.proxyDpop(Method.GET, "central", "/configuration/clients", token, query = List("tenantId" -> "default"))
        body <- listed.obj
      yield assertTrue(listed.status == Status.Ok) &&
        assertTrue(body.get("clients").isDefined)
          .label("the body must be central's own listing, not edge's")
    },
    test("it writes, reads back and deletes a resource the way provision does") {
      for
        token <- provisionerToken
        edgeApi <- ZIO.service[EdgeApi]
        resourceId <- CentralApi.id("e2e-provisioned")
        created <- edgeApi.proxyDpop(
          Method.POST,
          "central",
          "/configuration/resources",
          token,
          body = Some(Fixtures.resource(resourceId, s"https://$resourceId.example.test")),
        )
        listed <- edgeApi.proxyDpop(Method.GET, "central", "/configuration/resources", token, query = List("tenantId" -> Fixtures.suiteTenant))
        body <- listed.obj
        removed <- edgeApi.proxyDpop(Method.DELETE, "central", "/configuration/resources", token, query = List("resourceId" -> resourceId))
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
        // Retried on a 5xx for the reason EdgeApi.syncConfiguration is: the sync makes central
        // call auth over a pooled connection, and one auth closed while it sat idle surfaces
        // here as a 500 on the first attempt.
        synced <- edgeApi.proxyDpop(Method.POST, "central", "/service/configuration/sync", token)
          .filterOrFail(_.status.isSuccess)(RuntimeException("sync did not succeed"))
          .retry(Schedule.recurs(3) && Schedule.spaced(1.second))
        flushed <- edgeApi.proxyDpop(Method.POST, "central", "/service/users/outbox/flush", token)
      yield assertTrue(synced.status.isSuccess, flushed.status.isSuccess)
    },
    // `users:read` is deliberately absent from the provisioner's permissions: a campaign writes
    // no users, and the resource secret this client replaces could read every one of them.
    test("it cannot read users, which its permissions do not cover") {
      for
        token <- provisionerToken
        edgeApi <- ZIO.service[EdgeApi]
        denied <- edgeApi.proxyDpop(Method.GET, "central", "/users", token, query = List("tenantId" -> "default"))
      yield assertTrue(denied.status == Status.Forbidden)
    },
    // The audience is enforced where it is decided: `resource://central` is issuable only
    // because the bootstrap put the provisioner in that resource's audience, and a resource it
    // was not added to is refused outright rather than answered with a token that would then be
    // someone else's to check. Edge, downstream of this, authorizes by the client's
    // permissions.
    test("a resource the provisioner is not in the audience of is not issuable") {
      for
        auth <- ZIO.service[OAuthClient]
        c <- config
        (refused, _) <- ProvisionerCredential.request(auth, c, List("resource://auth"))
      yield assertTrue(refused.response.status == Status.BadRequest) &&
        assertTrue(errorCode(refused).contains("invalid_target"))
          .label("RFC 8707 §2: an audience the client has no access to is invalid_target")
    },
  )

  /** The `error` code of a rejected token request; `None` when the request succeeded. */
  private def errorCode(result: TokenResult): Option[String] =
    result match
      case TokenResult.Failure(_, body) =>
        body.fromJson[Json.Obj].toOption.flatMap(_.get("error")).collect { case Json.Str(code) => code }
      case _: TokenResult.Success => None

  private def resources(body: Json.Obj): List[String] =
    body.get("resources").toList.flatMap:
      case Json.Arr(entries) => entries.toList.flatMap(_.asObject).flatMap(_.get("resourceId")).collect { case Json.Str(id) => id }
      case _ => Nil
