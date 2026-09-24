package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Header, Status, URL}
import zio.test.*

/** An edge fronting a client that authenticates by key rather than by secret, and whose
  * authorization requests must be signed (RFC 9101) and pushed (RFC 9126).
  *
  * The edge's own specs prove each rule against a stubbed `Client`. What only this level
  * shows is that the pieces line up across the services: that central hands the edge a private
  * key it can actually use, that auth verifies the assertion and the request object against
  * the public half the same registration published, and that a `request_uri` minted at `/par`
  * is the one `/authorize` resolves. Every one of those is a contract between two processes,
  * and a stub on either side of it can be made to agree with a mistake.
  */
object EdgePrivateKeyJwtSpec extends EdgeSpec(
      EdgeFixture.Config(
        resourceId = "e2e-edge-private-key-jwt",
        resourceUri = "http://localhost:9007",
        privateKeyJwt = true,
        requireSignedRequestObject = true,
        requirePushedAuthorizationRequests = true,
      ),
    ):

  def spec = suite("edge fronting a private_key_jwt client")(
    test("signs in through /par and a signed request object, with no secret anywhere") {
      for
        f <- fixture
        session <- signIn
      yield assertTrue(
        // Reaching a session at all means every step held: `/par` accepted the assertion,
        // stored the signed object, and `/authorize` resolved the reference -- then `/token`
        // accepted a second assertion for the code exchange.
        session.cookie.nonEmpty,
        // Central still issues a secret for a web client, and that it is never usable is
        // auth's rule, asserted by `PrivateKeyJwtSpec`. What matters here is that the edge
        // was given a key and signed in with it.
        f.signer.isDefined,
      )
    },
    test("the browser is redirected to a request_uri, never to the request itself") {
      for
        edgeApi <- edge
        f <- fixture
        started <- edgeApi.login(f.presetId)
        location <- ZIO.fromOption(started.header(Header.Location).map(_.url.encode))
          .orElseFail(RuntimeException(s"/login did not redirect (status=${started.status})"))
        url <- ZIO.fromEither(URL.decode(location)).mapError(RuntimeException(_))
      yield assertTrue(
        started.status == Status.Found,
        // RFC 9126 §6.2 buys nothing if the parameters ride along too: the reference has to
        // be the whole of what the user agent carries.
        url.queryParams.map.keySet == Set("client_id", "request_uri"),
        url.queryParams.getAll("request_uri").headOption.exists(_.startsWith("urn:ietf:params:oauth:request_uri:")),
        url.queryParams.getAll("client_id").contains(f.clientId),
      )
    },
  )
