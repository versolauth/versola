package versola.e2e.flows.headers

import versola.e2e.support.{*, given}
import zio.*
import zio.http.*
import zio.test.*

/** #473: the headers reach a browser through the real servers -- the main listener, its fallback
  * for an unknown path, and the mutual-TLS listener that terminates TLS itself -- not just through
  * the in-memory routes `SecurityHeadersSpec` in `util` runs.
  */
object SecurityHeadersSpec extends E2ESpec:

  private def tlsGet(auth: OAuthClient, path: String): Task[Response] =
    val ssl = ClientSSLConfig.FromClientAndServerCert(
      ClientSSLConfig.FromCertFile(auth.authMutualTlsTrustedCertificates),
      ClientSSLCertConfig.FromClientCertFile(auth.authMutualTlsClientCertificate, auth.authMutualTlsClientKey),
    )
    ZIO.scoped:
      for
        client <- Client.default.build.map(_.get[Client])
        url <- ZIO.fromEither(URL.decode(s"${auth.authMutualTlsUrl}$path")).mapError(RuntimeException(_))
        response <- Client.batched(Request.get(url)).provide(ZLayer.succeed(client.ssl(ssl)))
      yield response

  def spec = suite("Security headers (#473)")(
    test("the main listener's JSON responses carry the always-on headers and no framing policy") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.probe(Method.GET, s"${auth.issuer}/.well-known/openid-configuration")
      yield assertTrue(
        response.status == Status.Ok,
        response.rawHeader("X-Content-Type-Options").contains("nosniff"),
        response.rawHeader("Referrer-Policy").contains("no-referrer"),
        response.rawHeader("Permissions-Policy").nonEmpty,
        response.rawHeader("X-Frame-Options").isEmpty,
        // Plain http to the staged service, no proxy in front: nothing says the browser used https.
        response.rawHeader("Strict-Transport-Security").isEmpty,
      )
    },
    test("a path no route serves is answered with the same headers") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.probe(Method.GET, s"${auth.issuer}/no-such-endpoint")
      yield assertTrue(
        response.status == Status.NotFound,
        response.rawHeader("X-Content-Type-Options").contains("nosniff"),
      )
    },
    test("behind a TLS terminator the service sends HSTS") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        url <- ZIO.fromEither(URL.decode(s"${auth.issuer}/.well-known/openid-configuration")).mapError(RuntimeException(_))
        response <- ZIO.scoped:
          Client.default.build.map(_.get[Client]).flatMap: client =>
            Client.batched(Request.get(url).addHeader(Header.Custom("X-Forwarded-Proto", "https")))
              .provide(ZLayer.succeed(client))
      yield assertTrue(response.rawHeader("Strict-Transport-Security").exists(_.contains("max-age=")))
    },
    test("the mutual-TLS listener sends HSTS on its own, with no forwarding header") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        response <- tlsGet(auth, "/no-such-endpoint")
      yield assertTrue(
        response.rawHeader("Strict-Transport-Security").exists(_.contains("max-age=")),
        response.rawHeader("X-Content-Type-Options").contains("nosniff"),
      )
    },
  )
