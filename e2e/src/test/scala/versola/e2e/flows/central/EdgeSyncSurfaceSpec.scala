package versola.e2e.flows.central

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.{Base64, Date, UUID}

/** Central's edge-facing surface, `/edge/sync/<resource>` (#554), seen from a second edge registered
  * for a tenant of its own.
  *
  * An edge authenticates with the key central minted at registration, and is served the
  * configuration of the tenants bound to it and nothing of another tenant's: the point of the
  * surface is that a partner's edge, or one site of several, cannot read what it does not
  * front. The same token is refused everywhere else -- every `/configuration` route and
  * `/users/registrations` belong to auth and the admin console -- so a leak would have to go
  * through the one controller built for edges.
  */
object EdgeSyncSurfaceSpec extends CentralApiSpec:

  /** A second edge with a tenant bound to it, and one of each configuration kind in that
    * tenant next to one in the suite tenant, which this edge does not serve. */
  private case class SecondEdge(
      edgeId: String,
      token: String,
      tenantId: String,
      ownClient: String,
      otherClient: String,
      ownResource: String,
      otherResource: String,
      ownRole: String,
      otherRole: String,
      ownPermission: String,
      otherPermission: String,
      ownPreset: String,
      otherPreset: String,
  )

  /** What edge's `CentralSyncTokenService` presents: `RS256` with the registered key, naming
    * the edge in the `edge_id` header, and a `jti`, which central's verifier insists on. Built
    * with Nimbus directly, since e2e has no access to the services' own `JWT` helper. */
  private def edgeToken(edgeId: String, keyId: String, privateKey: String): Task[String] =
    ZIO.attempt:
      val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getUrlDecoder.decode(privateKey)))
      val now = Instant.now()
      val claims = JWTClaimsSet.Builder()
        .issuer("edge")
        .subject("edge")
        .audience("central")
        .jwtID(UUID.randomUUID().toString)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(60)))
        .build()
      val header = JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(keyId)
        .`type`(JOSEObjectType.JWT)
        .customParam("edge_id", edgeId)
        .build()
      val jwt = SignedJWT(header, claims)
      jwt.sign(RSASSASigner(key))
      jwt.serialize()

  /** A fixture write that was refused would leave the edge with nothing to be shown, and the
    * assertions would then report a missing row instead of the refusal. */
  private def write(central: CentralApi, path: String, body: Json.Obj): Task[Unit] =
    central.post(path, body).flatMap: result =>
      ZIO.fail(RuntimeException(s"POST $path refused: ${result.status} ${result.body}")).unless(result.status.isSuccess).unit

  /** What `Fixtures.client` registers, and so the one URI a preset of that client may name. */
  private val clientRedirectUri = "http://localhost:3000"

  private def awaitClient(central: CentralApi, tenantId: String, clientId: String): Task[Unit] =
    eventually(central.get(
      "/configuration/clients",
      "tenantId" -> tenantId,
    ).flatMap(_.items("clients")))(_.exists(_.str("id").contains(clientId))).flatMap: listed =>
      ZIO.fail(RuntimeException(s"central never listed client '$clientId'")).unless(listed.exists(_.str("id").contains(clientId))).unit

  private def register(central: CentralApi): Task[SecondEdge] =
    for
      edgeId <- CentralApi.id("e2e-edge")
      tenantId <- CentralApi.id("e2e-tenant")
      ownClient <- CentralApi.id("e2e-client")
      otherClient <- CentralApi.id("e2e-client")
      ownResource <- CentralApi.id("e2e-resource")
      otherResource <- CentralApi.id("e2e-resource")
      ownRole <- CentralApi.id("e2e-role")
      otherRole <- CentralApi.id("e2e-role")
      ownPermission <- CentralApi.permission("own")
      otherPermission <- CentralApi.permission("other")
      ownPreset <- CentralApi.id("e2e-preset")
      otherPreset <- CentralApi.id("e2e-preset")
      registered <- central.post("/configuration/edges", Json.Obj("id" -> Json.Str(edgeId)))
      keyId <- registered.stringAt("keyId")
      privateKey <- registered.stringAt("privateKey")
      token <- edgeToken(edgeId, keyId, privateKey)
      // Central authenticates an edge against a cache a Postgres notification refreshes after
      // the registration lands; what is awaited is the new edge being known at all.
      known <- eventually(central.withBearer(token).get("/edge/sync/dpop"))(_.status == Status.Ok)
      _ <- ZIO.fail(RuntimeException(s"central never authenticated the new edge: ${known.status} ${known.body}"))
        .unless(known.status == Status.Ok)
      // On `standard`, like the suite tenant: a new tenant defaults to `fapi2`, which refuses
      // the plain `client_secret` fixture client, and there would be nothing to sync.
      _ <- write(central, "/configuration/tenants", Fixtures.tenant(tenantId, edgeId = Some(edgeId), securityProfile = Some("standard")))
      _ <- SecurityProfiles.awaitProfile(central, tenantId, "standard")
      _ <- write(central, "/configuration/clients", Fixtures.client(ownClient, tenantId = tenantId))
      _ <- write(central, "/configuration/clients", Fixtures.client(otherClient))
      _ <- write(central, "/configuration/resources", Fixtures.resource(ownResource, s"https://$ownResource.example.com", tenantId = tenantId))
      _ <- write(central, "/configuration/resources", Fixtures.resource(otherResource, s"https://$otherResource.example.com"))
      _ <- write(central, "/configuration/roles", Fixtures.role(ownRole, tenantId = tenantId))
      _ <- write(central, "/configuration/roles", Fixtures.role(otherRole))
      _ <- write(central, "/configuration/permissions", Fixtures.permission(ownPermission, tenantId = tenantId))
      _ <- write(central, "/configuration/permissions", Fixtures.permission(otherPermission))
      // A preset is validated against its client's redirect URIs, read from central's cache:
      // the client has to be readable first, and the preset has to name a URI it registered.
      _ <- awaitClient(central, tenantId, ownClient)
      _ <- awaitClient(central, Fixtures.suiteTenant, otherClient)
      _ <- write(
        central,
        "/configuration/auth-request-presets",
        Fixtures.presets(ownClient, Fixtures.preset(ownPreset, redirectUri = clientRedirectUri)),
      )
      _ <- write(
        central,
        "/configuration/auth-request-presets",
        Fixtures.presets(otherClient, Fixtures.preset(otherPreset, redirectUri = clientRedirectUri)),
      )
    yield SecondEdge(
      edgeId = edgeId,
      token = token,
      tenantId = tenantId,
      ownClient = ownClient,
      otherClient = otherClient,
      ownResource = ownResource,
      otherResource = otherResource,
      ownRole = ownRole,
      otherRole = otherRole,
      ownPermission = ownPermission,
      otherPermission = otherPermission,
      ownPreset = ownPreset,
      otherPreset = otherPreset,
    )

  private def cleanup(central: CentralApi, edge: SecondEdge): UIO[Unit] =
    (central.delete("/configuration/clients", "clientId" -> edge.ownClient) *>
      central.delete("/configuration/clients", "clientId" -> edge.otherClient) *>
      central.delete("/configuration/resources", "resourceId" -> edge.ownResource) *>
      central.delete("/configuration/resources", "resourceId" -> edge.otherResource) *>
      central.delete("/configuration/roles", "tenantId" -> edge.tenantId, "roleId" -> edge.ownRole) *>
      central.delete("/configuration/roles", "tenantId" -> Fixtures.suiteTenant, "roleId" -> edge.otherRole) *>
      central.delete("/configuration/permissions", "tenantId" -> edge.tenantId, "permission" -> edge.ownPermission) *>
      central.delete("/configuration/permissions", "tenantId" -> Fixtures.suiteTenant, "permission" -> edge.otherPermission) *>
      central.delete("/configuration/tenants", "tenantId" -> edge.tenantId) *>
      central.delete("/configuration/edges", "edgeId" -> edge.edgeId)).ignore

  private def withSecondEdge[A](central: CentralApi)(use: SecondEdge => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(register(central))(cleanup(central, _))(use)

  /** The ids in the named array of a sync document, read as the edge would. Central serves
    * these from caches a Postgres notification refreshes after the write, so the reads below
    * poll until the edge's own row has arrived -- what is awaited is the write landing, and
    * the other tenant's row is then judged on the same snapshot. A refusal reads as no ids,
    * so the assertion then reports the missing own row rather than a parse error. */
  private def syncIds(central: CentralApi, edge: SecondEdge, resource: String, items: String, id: String)(own: String): Task[Set[String]] =
    val read = central.withBearer(edge.token).get(s"/edge/sync/$resource").flatMap: result =>
      if result.status == Status.Ok then result.items(items).map(_.flatMap(_.str(id)).toSet)
      else ZIO.succeed(Set.empty[String])
    eventually(read)(_.contains(own))

  def spec = suite("Central API: the edge-facing surface")(
    test("a second edge reads only the tenant bound to it, on every sync resource") {
      for
        central <- api
        result <- withSecondEdge(central): edge =>
          for
            clients <- syncIds(central, edge, "clients", "clients", "id")(edge.ownClient)
            resources <- syncIds(central, edge, "resources", "resources", "resourceId")(edge.ownResource)
            roles <- syncIds(central, edge, "roles", "roles", "id")(edge.ownRole)
            permissions <- syncIds(central, edge, "permissions", "permissions", "id")(edge.ownPermission)
            presets <- syncIds(central, edge, "auth-request-presets", "presets", "id")(edge.ownPreset)
          yield assertTrue(clients.contains(edge.ownClient), !clients.contains(edge.otherClient))
            .label("clients: the suite tenant is not bound to this edge, so its client must not appear") &&
            assertTrue(resources.contains(edge.ownResource), !resources.contains(edge.otherResource))
              .label("resources: a resource secret handed to the wrong edge lets it impersonate that resource") &&
            assertTrue(roles.contains(edge.ownRole), !roles.contains(edge.otherRole)).label("roles") &&
            assertTrue(permissions.contains(edge.ownPermission), !permissions.contains(edge.otherPermission)).label("permissions") &&
            assertTrue(presets.contains(edge.ownPreset), !presets.contains(edge.otherPreset))
              .label("presets: scoped through the client they belong to")
      yield result
    },
    test("an edge is served its own DPoP policy, the public JWKS, and the DPoP algorithms alone of the server metadata") {
      for
        central <- api
        result <- withSecondEdge(central): edge =>
          val asEdge = central.withBearer(edge.token)
          for
            dpop <- asEdge.get("/edge/sync/dpop")
            policy <- dpop.obj
            jwks <- asEdge.get("/edge/sync/jwks")
            keys <- jwks.obj
            metadata <- asEdge.get("/edge/sync/server-metadata")
            document <- metadata.obj
          yield assertTrue(dpop.status == Status.Ok, policy.bool("requireDpopNonce").contains(true))
            .label("a newly registered edge demands a nonce on every proxied call") &&
            assertTrue(jwks.status == Status.Ok, keys.fields.exists(_._1 == "keys")) &&
            assertTrue(metadata.status == Status.Ok, document.fields.map(_._1).forall(_ == "dpop_signing_alg_values_supported"))
              .label("an edge acts on the DPoP algorithm list and is sent nothing else of the document")
      yield result
    },
    test("an edge token is refused on the routes that are auth's and the admin console's") {
      for
        central <- api
        result <- withSecondEdge(central): edge =>
          val asEdge = central.withBearer(edge.token)
          for
            clientsSync <- asEdge.get("/configuration/clients/sync")
            signingKeys <- asEdge.get("/configuration/jwks/signing-keys/sync")
            registration <- asEdge.post("/users/registrations", Json.Obj("email" -> Json.Str("edge@example.com")))
            tenants <- asEdge.get("/configuration/tenants")
            own <- asEdge.get("/edge/sync/clients")
          yield assertTrue(clientsSync.status == Status.Forbidden)
            .label("the sync route auth reads returns every tenant: an edge is not let near it") &&
            assertTrue(signingKeys.status == Status.Forbidden).label("the private halves are auth's alone") &&
            assertTrue(registration.status == Status.Forbidden).label("only auth indexes a registered user") &&
            assertTrue(tenants.status == Status.Forbidden).label("an admin route is no different") &&
            assertTrue(own.status == Status.Ok).label("the same token is what the edge-facing surface accepts")
      yield result
    },
    test("a token that only claims to be an edge is not authenticated") {
      for
        central <- api
        result <- withSecondEdge(central): edge =>
          for
            // Signed with the real key but naming an edge central never registered: the key
            // lookup fails before the signature is ever checked.
            registered <- central.post("/configuration/edges", Json.Obj("id" -> Json.Str(s"${edge.edgeId}-other")))
            keyId <- registered.stringAt("keyId")
            privateKey <- registered.stringAt("privateKey")
            _ <- central.delete("/configuration/edges", "edgeId" -> s"${edge.edgeId}-other")
            stranger <- edgeToken(s"${edge.edgeId}-other", keyId, privateKey)
            onEdgeSurface <- central.withBearer(stranger).get("/edge/sync/clients")
            onConfiguration <- central.withBearer(stranger).get("/configuration/clients/sync")
            anonymous <- central.anonymous.get("/edge/sync/clients")
            asAdmin <- central.get("/edge/sync/clients")
          yield assertTrue(onEdgeSurface.status == Status.Unauthorized, onConfiguration.status == Status.Unauthorized)
            .label("an unregistered edge gets 401, not the 403 a known edge gets: it is told nothing") &&
            assertTrue(anonymous.status == Status.Unauthorized, asAdmin.status == Status.Unauthorized)
              .label("the admin credential is not an edge either")
      yield result
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
