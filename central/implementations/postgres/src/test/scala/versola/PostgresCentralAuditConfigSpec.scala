package versola

import com.typesafe.config.ConfigFactory
import versola.central.CentralConfig
import versola.util.audit.AuditEvents
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.test.*

/** The audit log has no key in central's config: its two switches are a tenant's Security settings, and the rest is fixed. A config
  * written before that still has the `audit` block, which must not stop the start. */
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

  private val oldBlock =
    """audit { retention = "14 days", overflow = "block", block-timeout = "500 millis", events { admin-user-viewed = false } }"""

  private def load(extra: String) =
    ConfigProvider.fromTypesafeConfigZIO(ConfigFactory.parseString(required + extra).resolve()).map(_.kebabCase)
      .flatMap(_.load(deriveConfig[CentralConfig]))

  def spec = suite("central audit config")(
    test("a config that has no audit block loads") {
      for config <- load("")
      yield assertTrue(config.configurationCacheRefreshInterval == 5.minutes)
    },
    test("a config that still has the old audit block loads, and the block changes nothing") {
      for
        without <- load("")
        old <- load(oldBlock)
        invalid <- load("""audit { retention = "0 days", overflow = "wait" }""")
      // the config holds secrets as arrays, which do not compare equal: the rest of it is what the block would have changed
      yield assertTrue(
        old.auth == without.auth,
        old.userOutbox == without.userOutbox,
        old.configurationCacheRefreshInterval == 5.minutes,
        invalid.userOutbox == without.userOutbox,
      )
    },
    test("the log is kept 7 days and a full queue drops the event, whatever the config says") {
      assertTrue(
        AuditEvents.Settings.standard == AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.drop, blockTimeout = 2.seconds),
        // the table is partitioned far enough ahead to keep what the retention does
        AuditEvents.Sizing.standard.partitionsAhead >= AuditEvents.Settings.standard.retention.toDays,
      )
    },
  )
