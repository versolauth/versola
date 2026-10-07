package versola.central

import zio.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.URL
import zio.test.*

/** The `client-certificates` blocks `scripts/gen-env.scala` writes, parsed the way the running
  * application parses central's config (kebab-case HOCON through `deriveConfig`). */
object ClientCertificatesConfigSpec extends ZIOSpecDefault:

  private given DeriveConfig[URL] = DeriveConfig[String]
    .mapOrFail(URL.decode(_).left.map(ex => zio.Config.Error.InvalidData(message = ex.getMessage)))

  private val descriptor = deriveConfig[CentralConfig.ClientCertificatesConfig].nested("client-certificates")

  private def parse(hocon: String) =
    TypesafeConfigProvider.fromHoconString(hocon).kebabCase.load(descriptor)

  def spec = suite("client-certificates")(
    test("parses the step-ca block docker-local and vps are given") {
      for config <- parse(
          """client-certificates {
            |  validity     = "14 days"
            |  renew-before = "4 days"
            |  step-ca {
            |    url             = "https://step-ca:9000"
            |    root-certificate = "/app/ca/root_ca.crt"
            |    provisioner     = "central"
            |    provisioner-key = "/app/ca/provisioner.json"
            |  }
            |}""".stripMargin,
        )
      yield assertTrue(
        config.validity == 14.days,
        config.renewBefore == 4.days,
        config.checkInterval == 1.hour,
        config.stepCa.map(_.provisioner) == Some("central"),
        config.stepCa.map(_.provisionerKey) == Some("/app/ca/provisioner.json"),
        config.certManager.isEmpty,
      )
    },
    test("parses the cert-manager block k8s is given, with the service-account defaults") {
      for config <- parse(
          """client-certificates {
            |  validity     = "14 days"
            |  renew-before = "4 days"
            |  cert-manager {
            |    issuer-name = "versola-client-ca"
            |  }
            |}""".stripMargin,
        )
      yield assertTrue(
        config.certManager.map(_.issuerName) == Some("versola-client-ca"),
        config.certManager.map(_.issuerKind) == Some("ClusterIssuer"),
        config.certManager.map(_.tokenPath) == Some("/var/run/secrets/kubernetes.io/serviceaccount/token"),
        config.stepCa.isEmpty,
      )
    },
  )
