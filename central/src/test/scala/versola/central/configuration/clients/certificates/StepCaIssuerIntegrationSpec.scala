package versola.central.configuration.clients.certificates

import versola.central.CentralConfig.StepCaConfig
import versola.central.configuration.clients.{ClientId, MutualTlsAuth, MutualTlsSubjectType}
import zio.*
import zio.http.{Client, URL}
import zio.test.*

/** Against a real step-ca; skipped unless `STEP_CA_URL` is set. To run it:
  * {{{
  *   docker compose up -d step-ca   # a CA whose JWK provisioner "central" exists, see
  *                                  # docker/versola-tools/compose.fragment.yml.template
  *   STEP_CA_URL=https://localhost:9000 STEP_CA_ROOT=<root_ca.crt> \
  *   STEP_CA_PROVISIONER_KEY=<central-provisioner.json> sbt "central/testOnly *StepCaIssuerIntegrationSpec"
  * }}}
  */
object StepCaIssuerIntegrationSpec extends ZIOSpecDefault:

  private def env(name: String) = ZIO.fromOption(Option(java.lang.System.getenv(name))).orElseFail(RuntimeException(s"$name is not set"))

  private def issuer =
    for
      url <- env("STEP_CA_URL")
      root <- env("STEP_CA_ROOT")
      key <- env("STEP_CA_PROVISIONER_KEY")
      client <- ZIO.service[Client]
      issuer <- StepCaIssuer.make(StepCaConfig(URL.decode(url).toOption.get, root, "central", key), client)
    yield issuer

  def spec = suite("StepCaIssuer against a real CA")(
    test("signs a request for a full subject_dn registration, keeping every RDN") {
      for
        issuer <- issuer
        subject <- ZIO.fromEither(ClientCertificateRequests.subjectFor(
          ClientId("mobile-app"),
          MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=mobile-app,O=Versola,C=KZ"),
        )).mapError(RuntimeException(_))
        generated <- ClientCertificateRequests.generate(subject, 14.days)
        chain <- issuer.sign(generated.request)
        material <- ZIO.fromEither(versola.util.PrivateClientCertificate(chain + "\n" + generated.privateKeyPem).material).mapError(RuntimeException(_))
      yield assertTrue(material.subjectValues("subject_dn") == Set("CN=mobile-app,O=Versola,C=KZ"))
    },
    test("signs a request for the subject mtlsAuth registers") {
      for
        url <- env("STEP_CA_URL")
        root <- env("STEP_CA_ROOT")
        key <- env("STEP_CA_PROVISIONER_KEY")
        client <- ZIO.service[Client]
        issuer <- StepCaIssuer.make(
          StepCaConfig(URL.decode(url).toOption.get, root, "central", key),
          client,
        )
        subject <- ZIO.fromEither(ClientCertificateRequests.subjectFor(
          ClientId("mobile-app"),
          MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "mobile-app.clients.versola.test"),
        )).mapError(RuntimeException(_))
        generated <- ClientCertificateRequests.generate(subject, 14.days)
        chain <- issuer.sign(generated.request)
        certificate = versola.util.PrivateClientCertificate(chain + "\n" + generated.privateKeyPem)
        material <- ZIO.fromEither(certificate.material).mapError(RuntimeException(_))
      yield assertTrue(
        material.subjectValues("san_dns") == Set("mobile-app.clients.versola.test"),
        material.leaf.getNotAfter.toInstant.isAfter(java.time.Instant.now().plusSeconds(13 * 24 * 3600L)),
      )
    },
  ).provide(Client.default) @@ (if java.lang.System.getenv("STEP_CA_URL") == null then TestAspect.ignore else TestAspect.identity) @@ TestAspect.withLiveClock
