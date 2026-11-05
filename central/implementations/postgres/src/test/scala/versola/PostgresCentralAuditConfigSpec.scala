package versola

import com.typesafe.config.ConfigFactory
import versola.central.CentralConfig
import versola.util.audit.AuditEvents
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.test.*

/** The `audit` block of central's config (#469): every key has a default, so a deployment that sets none starts as it did. */
object PostgresCentralAuditConfigSpec extends ZIOSpecDefault:

  import PostgresCentralApp.given

  private val required =
    """
      |client-secrets-secret = "AAAAAAAAAAAAAAAAAAAAAA"
      |secret-key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      |auth { url = "http://localhost:9003" }
      |user-outbox { poll-interval = "1 second", batch-size = 1, lease = "1 minute", max-backoff = "1 minute", max-attempts = 3 }
      |configuration-cache-refresh-interval = "5 minutes"
      |""".stripMargin

  private def load(extra: String) =
    ConfigProvider.fromTypesafeConfigZIO(ConfigFactory.parseString(required + extra).resolve()).map(_.kebabCase)
      .flatMap(_.load(deriveConfig[CentralConfig]))

  def spec = suite("central audit config")(
    test("without an audit block the log is kept 7 days, a full queue holds an event up to 2 seconds, and a look at a user is an event") {
      for config <- load("")
      yield assertTrue(
        config.audit == CentralConfig.AuditConfig(),
        config.audit.retention == 7.days,
        config.audit.overflow == AuditEvents.Overflow.drop,
        config.audit.blockTimeout == 2.seconds,
        config.audit.events.adminUserViewed,
      )
    },
    test("the keys of the block are read, and become the writer's settings") {
      for config <- load("""audit { retention = "14 days", overflow = "drop", block-timeout = "500 millis", events { admin-user-viewed = false } }""")
      yield assertTrue(
        config.audit.settings == AuditEvents.Settings(retention = 14.days, overflow = AuditEvents.Overflow.drop, blockTimeout = 500.millis),
        !config.audit.events.adminUserViewed,
      )
    },
    test("a block that sets one key leaves the others at their defaults") {
      for config <- load("""audit { retention = "10 days" }""")
      yield assertTrue(
        config.audit.retention == 10.days,
        config.audit.overflow == AuditEvents.Overflow.drop,
        config.audit.events.adminUserViewed,
      )
    },
    test("an overflow that is neither block nor drop stops the start and names the key") {
      for refused <- load("""audit { overflow = "wait" }""").either
      yield assertTrue(refused.left.exists(_.toString.contains("audit.overflow is 'block' or 'drop', not 'wait'")))
    },
  )
