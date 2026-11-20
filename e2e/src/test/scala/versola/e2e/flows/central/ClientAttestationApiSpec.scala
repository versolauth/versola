package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** `/configuration/clients/attestation` (#423): a native client's app attestation policy.
  *
  * The unit suites prove the validation and the status mapping against stubs. What only this level shows is that the policy
  * survives the Postgres round trip with its builds, that the client's kind decides who may have one, and that the call is
  * served under the administrator's credentials the way every other `/configuration` route is.
  */
object ClientAttestationApiSpec extends CentralApiSpec:

  private val clientsPath = "/configuration/clients"
  private val path = "/configuration/clients/attestation"

  private val digest = "ab" * 32

  /** A native client fronted by edge: `native` with `tls_client_auth`, the only kind that has a policy. */
  private def mutualTlsNative(id: String, certificate: EdgeCertificate): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(Fixtures.suiteTenant),
      "id" -> Json.Str(id),
      "clientName" -> Fixtures.text("Attested native"),
      "redirectUris" -> Json.Arr(Json.Str("https://app.test/callback")),
      "allowedScopes" -> Json.Arr(Json.Str("openid")),
      "accessTokenTtl" -> Json.Num(3600),
      "authMethod" -> Json.Str("tls_client_auth"),
      "mtlsAuth" -> Fixtures.mutualTlsAuth("subject_dn", s"CN=$id"),
      // What edge presents as this client: a native client fronted by edge is registered with it (the app keeps no credential).
      "edgeClientCertificate" -> Json.Str(certificate.edgeClientCertificate),
      "issueEdgeClientCertificate" -> Json.Bool(false),
      "enrollEdgeClientCertificate" -> Json.Bool(false),
      "theme" -> Json.Str("default"),
      "otpTemplateId" -> Json.Str("default"),
      "frontChannelLogoutSessionRequired" -> Json.Bool(false),
      "dpopSigningAlgs" -> Json.Arr(),
    )

  private def publicNative(id: String, certificate: EdgeCertificate): Json.Obj =
    Json.Obj(
      mutualTlsNative(id, certificate).fields
        .filterNot((name, _) => name == "mtlsAuth" || name == "edgeClientCertificate")
        .map:
          case ("authMethod", _) => "authMethod" -> Json.Str("none")
          case other => other,
    )

  private def policy(mode: String, appIds: Seq[String] = Seq("ABCDE12345.com.example.app"), digests: Seq[String] = Seq(digest)): Json.Obj =
    Json.Obj(
      "mode" -> Json.Str(mode),
      "ios" -> Json.Obj("appIds" -> Json.Arr(appIds.map(Json.Str(_))*), "environment" -> Json.Str("production")),
      "android" -> Json.Arr(
        Json.Obj("packageName" -> Json.Str("com.example.app"), "certificateDigests" -> Json.Arr(digests.map(Json.Str(_))*)),
      ),
    )

  /** The client is registered through its own endpoint, and gone when the test is over. */
  private def withClient[A](central: CentralApi, body: Json.Obj)(use: String => Task[A]): Task[A] =
    val id = body.str("id").getOrElse(throw IllegalArgumentException("fixture has no id"))
    ZIO.acquireReleaseWith(
      central.post(s"$clientsPath/native", body).flatMap: created =>
        ZIO.fail(RuntimeException(s"the fixture client was not registered (${created.status}): ${created.body}"))
          .unless(created.status == Status.Created),
    )(_ => central.delete(clientsPath, "clientId" -> id).ignore)(_ => use(id))

  private def withMtlsNative[A](central: CentralApi, id: String)(use: String => Task[A]): Task[A] =
    EdgeCertificate.make(commonName = id).flatMap(certificate => withClient(central, mutualTlsNative(id, certificate))(use))

  def spec = suite("Central API: app attestation policy")(
    test("a client with no policy reads as off over no builds") {
      for
        central <- api
        id <- CentralApi.id("attested")
        read <- withMtlsNative(central, id)(id => central.get(path, "clientId" -> id).flatMap(r => r.obj.map(r.status -> _)))
      yield assertTrue(read._1 == Status.Ok, read._2.str("mode").contains("off"), read._2.arr("android").isEmpty)
    },
    test("a policy is stored with its builds, the digest in one form, and read back") {
      for
        central <- api
        id <- CentralApi.id("attested")
        pasted = digest.toUpperCase.grouped(2).mkString(":")
        outcome <- withMtlsNative(central, id) { id =>
          for
            put <- central.put(path, policy("enforce", digests = Seq(pasted)), "clientId" -> id)
            read <- central.get(path, "clientId" -> id).flatMap(_.obj)
          yield (put.status, read)
        }
        android = outcome._2.objs("android").headOption
      yield assertTrue(
        outcome._1 == Status.Ok,
        outcome._2.str("mode").contains("enforce"),
        outcome._2.obj("ios").flatMap(_.strings("appIds").headOption).contains("ABCDE12345.com.example.app"),
        android.map(_.strings("certificateDigests")).contains(Set(digest)),
      )
    },
    test("a second put replaces the first, and a delete puts the client back to off") {
      for
        central <- api
        id <- CentralApi.id("attested")
        outcome <- withMtlsNative(central, id) { id =>
          for
            _ <- central.put(path, policy("enforce"), "clientId" -> id)
            _ <- central.put(path, policy("report"), "clientId" -> id)
            replaced <- central.get(path, "clientId" -> id).flatMap(_.obj)
            deleted <- central.delete(path, "clientId" -> id)
            after <- central.get(path, "clientId" -> id).flatMap(_.obj)
          yield (replaced.str("mode"), deleted.status, after.str("mode"))
        }
      yield assertTrue(outcome == (Some("report"), Status.NoContent, Some("off")))
    },
    test("report or enforce over no builds is refused, and nothing is stored") {
      for
        central <- api
        id <- CentralApi.id("attested")
        outcome <- withMtlsNative(central, id) { id =>
          for
            refused <- central.put(path, Json.Obj("mode" -> Json.Str("enforce"), "android" -> Json.Arr()), "clientId" -> id)
            after <- central.get(path, "clientId" -> id).flatMap(_.obj)
          yield (refused.status, after.str("mode"))
        }
      yield assertTrue(outcome == (Status.BadRequest, Some("off")))
    },
    test("a malformed App ID is refused") {
      for
        central <- api
        id <- CentralApi.id("attested")
        refused <- withMtlsNative(central, id): id =>
          central.put(path, policy("report", appIds = Seq("com.example.app")), "clientId" -> id)
      yield assertTrue(refused.status == Status.BadRequest, refused.body.contains("ios.appIds"))
    },
    test("a client that edge does not front has no policy to set or read") {
      for
        central <- api
        id <- CentralApi.id("attested")
        certificate <- EdgeCertificate.make()
        outcome <- withClient(central, publicNative(id, certificate)) { id =>
          for
            put <- central.put(path, policy("enforce"), "clientId" -> id)
            get <- central.get(path, "clientId" -> id)
          yield (put.status, get.status)
        }
      yield assertTrue(outcome == (Status.UnprocessableEntity, Status.UnprocessableEntity))
    },
    test("a client that does not exist is not found") {
      for
        central <- api
        get <- central.get(path, "clientId" -> "attested-missing")
        put <- central.put(path, policy("report"), "clientId" -> "attested-missing")
        delete <- central.delete(path, "clientId" -> "attested-missing")
      yield assertTrue(get.status == Status.NotFound, put.status == Status.NotFound, delete.status == Status.NotFound)
    },
    test("the administrator's credentials are required") {
      for
        central <- api
        refused <- central.withCredentials("nobody", "wrong").get(path, "clientId" -> "attested-missing")
      yield assertTrue(refused.status == Status.Unauthorized)
    },
  )
