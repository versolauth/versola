package versola

import com.typesafe.config.ConfigFactory
import versola.util.CoreConfig
import versola.util.audit.AuditEvents
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.*
import zio.test.*

/** The audit log has no key in auth's config: its two switches are a tenant's Security settings, and the rest is fixed. An `auth.conf`
  * written before that still has the `audit` block `scripts/gen-env.scala` used to write, and auth must start with it. */
object PostgresOAuthAppAuditConfigSpec extends ZIOSpecDefault:

  /** The part of the config the old block sat next to: top-level sections are read by name, the way `CoreConfig` is. */
  private case class Neighbours(par: Option[CoreConfig.ParConfig], configurationCacheRefreshInterval: Duration)

  private val oldBlock =
    """
      |audit {
      |  retention     = "7 days"
      |  overflow      = "drop"
      |  block-timeout = "2 seconds"
      |  events {
      |    token-refresh     = false
      |    authorize-success = true
      |    par-success       = true
      |  }
      |}
      |""".stripMargin

  private val config =
    """
      |configuration-cache-refresh-interval = "5 minutes"
      |par { request-uri-ttl = "60 seconds", max-request-size = 8192 }
      |""".stripMargin

  private def load(text: String) =
    ZIO.attempt(ConfigFactory.parseString(text).resolve()).orDie
      .flatMap(parsed => ConfigProvider.fromTypesafeConfigZIO(parsed).orDie)
      .flatMap(provider => provider.kebabCase.load(deriveConfig[Neighbours]))

  def spec = suite("auth's audit config")(
    test("a config that still has the old audit block is read as it is without it") {
      for
        without <- load(config)
        old <- load(config + oldBlock)
        broken <- load(config + """audit { retention = "0 days", overflow = "wait", events { token-refresh = 3 } }""")
      yield assertTrue(old == without, broken == without, old.par.exists(_.requestUriTtl == 60.seconds))
    },
    test("the log is kept 7 days and a full queue drops the event, whatever the config says") {
      assertTrue(
        AuditEvents.Settings.standard == AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.drop, blockTimeout = 2.seconds),
        AuditEvents.Sizing.standard.partitionsAhead >= AuditEvents.Settings.standard.retention.toDays,
      )
    },
  )
