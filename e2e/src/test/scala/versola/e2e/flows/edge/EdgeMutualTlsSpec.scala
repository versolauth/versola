package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.test.*

/** An edge fronting a client that authenticates by certificate rather than by secret -- the
  * credential `SSOClient.ClientCredential.MutualTls` carries, and the one the security fix on
  * `SSOClient.scala` is about: presenting it is a TLS handshake, not a request, and that
  * handshake's far side has to be authenticated or the certificate (and the session it opens)
  * would be handed to whatever answered the address.
  *
  * `EdgeFixture.Config.mutualTls` points `versola-internal-url` nowhere special -- it is the
  * same address every other edge spec's `/token` call already goes through (see
  * `scripts/gen-env.scala`'s nginx). What is special about this one is only the credential:
  * central hands the edge a certificate instead of a secret or a signing key, and reaching a
  * session at all here means edge's SSOClient completed a real TLS handshake against that
  * nginx, presented the certificate, validated nginx's own server certificate against
  * `versola-internal-trusted-certificates`, and had the result -- forwarded as a header, RFC
  * 8705 §6.5 -- accepted by auth for the client central registered it for.
  *
  * The unit suites (`SSOClientMutualTlsHandshakeSpec`) already prove the handshake itself
  * against a TLS server standing in for whatever terminates it. What only this level shows is
  * that the pieces line up across the real services: that central's `edgeClientCertificate`
  * survives the trip through the sync encryption `OAuthClientsSyncClient` decrypts, that the
  * certificate it hands edge is the one nginx actually presents, and that auth's own
  * `self_signed_tls_client_auth` matching accepts the header nginx forwards.
  *
  * Where it stops: a session, not a proxied request. The token auth issues this client is
  * bound to the certificate (RFC 8705 §3, `cnf.x5t#S256`), and edge cannot use such a
  * session -- see the PR description and the follow-up issue. Proxying is therefore not
  * asserted here rather than asserted broken.
  */
object EdgeMutualTlsSpec extends EdgeSpec(
      EdgeFixture.Config(
        resourceId = "e2e-edge-mutual-tls",
        // Distinct from every other edge spec's resource URI -- see EdgePrivateKeyJwtSpec's
        // comment on why that collision 500s instead of merely conflicting.
        resourceUri = UpstreamStub.uriOn(9107),
        mutualTls = true,
      ),
    ):

  def spec = suite("edge fronting a self-signed mutual-TLS client")(
    test("signs in with no secret anywhere, over a real TLS handshake through nginx") {
      for
        f <- fixture
        session <- signIn
      yield assertTrue(
        // Reaching a session at all means /token accepted the certificate central handed
        // this edge -- the whole chain from registration to the handshake held.
        session.cookie.nonEmpty,
        // The fixture generated a certificate rather than a secret being usable: confirms
        // this test is actually exercising the credential it claims to.
        f.certificate.isDefined,
      )
    },
  )
