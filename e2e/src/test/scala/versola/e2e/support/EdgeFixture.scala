package versola.e2e.support

import zio.*
import zio.http.Client
import zio.json.ast.Json
import zio.test.ZIOSpec

import java.util.UUID

/** Everything an edge test needs: a client the edge fronts, a login preset, a user who can
  * sign in, and a proxied resource with endpoints the user's role does — and does not — reach.
  *
  * Registered in central and then pushed into auth and edge with explicit cache syncs, because
  * neither service picks up a configuration change on its own inside a test's lifetime.
  */
case class EdgeFixture(
    clientId: String,
    clientSecret: String,
    presetId: String,
    postLoginRedirectUri: String,
    userId: UUID,
    login: String,
    password: String,
    roleId: String,
    resourceId: String,
    resourceUri: String,
    /** Endpoint ids by the name the spec gave them, so tests never spell out a UUID. */
    endpoints: Map[String, String],
):
  def endpoint(name: String): String =
    endpoints.getOrElse(name, throw java.util.NoSuchElementException(s"No endpoint registered under '$name'"))

object EdgeFixture:

  /** One endpoint of the proxied resource, and whether the fixture's role reaches it.
    *
    * `permitted = false` is what makes a 403 test meaningful: the endpoint exists and routes,
    * and the only reason the call fails is edge's deny-by-default permission check.
    */
  case class Endpoint(
      name: String,
      method: String = "GET",
      path: String,
      permitted: Boolean = true,
      fetchUserInfo: Boolean = false,
      allow: Option[String] = None,
      inject: List[Json] = Nil,
      stepUpCondition: Option[String] = None,
      stepUpAcr: Option[String] = None,
      maxAge: Option[Int] = None,
  )

  /** @param resourceId  a stable id, so a run that failed before cleaning up cannot leave a
    *                    second resource behind under a fresh name.
    * @param resourceUri the upstream origin. Every spec must claim a different one: auth
    *                    resolves a token's audience by looking a resource up by URI, so two
    *                    resources sharing one would be ambiguous.
    */
  case class Config(
      resourceId: String,
      resourceUri: String,
      endpoints: List[Endpoint] = Nil,
      scopes: Set[String] = Set("openid", "email", "offline_access"),
      presetScope: Set[String] = Set("openid"),
      postLogoutRedirectUri: Option[String] = None,
      cookiePath: Option[String] = None,
  )

  def layer(config: Config): ZLayer[OAuthClient & CentralApi & EdgeApi, Throwable, EdgeFixture] =
    ZLayer.fromZIO(make(config))

  def make(config: Config): ZIO[OAuthClient & CentralApi & EdgeApi, Throwable, EdgeFixture] =
    for
      auth <- ZIO.service[OAuthClient]
      central <- ZIO.service[CentralApi]
      edge <- ZIO.service[EdgeApi]

      clientId <- CentralApi.id("edge-client")
      presetId <- CentralApi.id("edge-preset")
      roleId <- CentralApi.id("edge-role")
      login <- CentralApi.login("edge-user")
      password = s"Edge-${login.takeRight(8)}-1!"
      endpointIds <- ZIO.foreach(config.endpoints)(e => CentralApi.uuid.map(id => e.name -> id.toString))
      byName = endpointIds.toMap

      // A previous run that failed before its cleanup would otherwise leave a resource
      // holding this URI, and central resolves a token's audience by URI.
      _ <- central.delete("/configuration/resources", "resourceId" -> config.resourceId)

      registered <- auth.registerClient(
        clientId,
        "Edge Test Client",
        redirectUris = Set(edge.completeUri),
        allowedScopes = config.scopes,
        authFlow = Some(Flows.loginPasswordAuthFlow),
      ).success

      userId <- auth.registerUser(login = Some(login))
      _ <- auth.flushUserOutbox()
      _ <- auth.setUserPassword(userId, password)

      _ <- central.post(
        "/configuration/resources",
        Fixtures.resource(
          resourceId = config.resourceId,
          resource = config.resourceUri,
          audience = Set(clientId),
          endpoints = config.endpoints.map(e =>
            Fixtures.endpoint(
              id = byName(e.name),
              method = e.method,
              path = e.path,
              fetchUserInfo = e.fetchUserInfo,
              allow = e.allow,
              inject = e.inject,
              stepUpCondition = e.stepUpCondition,
              stepUpAcr = e.stepUpAcr,
              maxAge = e.maxAge,
            ),
          ),
        ),
      ).flatMap(expect("register the proxied resource"))

      // One permission covering every endpoint the fixture's user is meant to reach. Edge
      // denies by default, so the endpoints left out of this set are unreachable without any
      // further configuration.
      permission <- CentralApi.permission("edge")
      _ <- central.post(
        "/configuration/permissions",
        Fixtures.permission(
          permission,
          endpointIds = config.endpoints.filter(_.permitted).map(e => byName(e.name)).toSet,
        ),
      ).flatMap(expect("register the endpoint permission"))

      _ <- central.post("/configuration/roles", Fixtures.role(roleId, permissions = Set(permission)))
        .flatMap(expect("register the role"))

      _ <- auth.assignUserRoles(userId, Set(roleId))
      _ <- auth.flushUserOutbox()

      _ <- central.post(
        "/configuration/auth-request-presets",
        Fixtures.presets(
          clientId,
          Fixtures.preset(
            presetId,
            redirectUri = edge.completeUri,
            postLoginRedirectUri = postLoginRedirectUri,
            postLogoutRedirectUri = config.postLogoutRedirectUri,
            scope = config.presetScope,
            cookiePath = config.cookiePath,
          ),
        ),
      ).flatMap(expect("register the login preset"))

      _ <- auth.syncConfiguration()
      _ <- edge.syncConfiguration
    yield EdgeFixture(
      clientId = clientId,
      clientSecret = registered.secret,
      presetId = presetId,
      postLoginRedirectUri = postLoginRedirectUri,
      userId = userId,
      login = login,
      password = password,
      roleId = roleId,
      resourceId = config.resourceId,
      resourceUri = config.resourceUri,
      endpoints = byName,
    )

  /** Where a completed edge login sends the browser. Any absolute URI the client is allowed to
    * be redirected to works; this one is the app origin the rest of the e2e setup uses.
    */
  val postLoginRedirectUri = "http://localhost:3000"

  private def expect(what: String)(result: ApiResult): Task[Unit] =
    ZIO.unless(result.status.isSuccess)(
      ZIO.fail(RuntimeException(s"Failed to $what: status=${result.status} body=${result.body}")),
    ).unit

/** Base class for the edge specs: the edge's own web login, its logout endpoints and its
  * resource proxy. Each spec declares the resource it proxies, which is registered once for
  * the whole suite.
  */
abstract class EdgeSpec(config: EdgeFixture.Config)
    extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture]:

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    clients >+> EdgeFixture.layer(config)

  val edge: URIO[EdgeApi, EdgeApi] = ZIO.service[EdgeApi]
  val auth: URIO[OAuthClient, OAuthClient] = ZIO.service[OAuthClient]
  val central: URIO[CentralApi, CentralApi] = ZIO.service[CentralApi]
  val fixture: URIO[EdgeFixture, EdgeFixture] = ZIO.service[EdgeFixture]

  /** A fresh browser session for the fixture's user. */
  val signIn: ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, EdgeSession] =
    for
      edgeApi <- edge
      authApi <- auth
      f <- fixture
      session <- edgeApi.browserLogin(authApi, f.presetId, f.login, f.password)
    yield session
