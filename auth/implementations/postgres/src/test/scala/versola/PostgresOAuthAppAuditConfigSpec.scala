package versola

import com.typesafe.config.ConfigFactory
import versola.audit.AuthAudit
import versola.util.audit.AuditEvents
import zio.*
import zio.config.typesafe.*
import zio.test.*

import java.nio.file.{Files, Paths}

/** The `audit` block of auth's config: every key is required, the block `scripts/gen-env.scala` writes parses into the settings the writer
  * runs with, and a value the partitions cannot honour stops the start naming the key. */
object PostgresOAuthAppAuditConfigSpec extends ZIOSpecDefault:

  /** The block as gen-env writes it, from its source. */
  private val generated: String =
    val file = "scripts/gen-env.scala"
    val path = List(file, s"../../$file", s"../$file", s"../../../$file").map(Paths.get(_)).find(Files.exists(_)).get
    val lines = Files.readAllLines(path).toArray.toList.map(_.toString)
    val from = lines.indexWhere(_.trim.startsWith("|audit {"))
    val to = lines.indexWhere(_.trim.startsWith("|# Antifraud"), from)
    lines.slice(from, to).map(_.trim.stripPrefix("|")).mkString("\n")

  /** The settings of the writer and the kinds a deployment left out, as the app loads them. */
  private def loadBoth(config: String): IO[Config.Error, (AuditEvents.Settings, AuthAudit.Tiers)] =
    ZIO.attempt(ConfigFactory.parseString(config).resolve()).orDie
      .flatMap(parsed => ConfigProvider.fromTypesafeConfigZIO(parsed).orDie)
      .flatMap(provider =>
        (ZIO.service[AuditEvents.Settings] <*> ZIO.service[AuthAudit.Tiers]).provide(
          PostgresOAuthApp.auditSettings,
          ZLayer.succeed(provider.kebabCase),
        ),
      )

  private def load(config: String): IO[Config.Error, AuditEvents.Settings] = loadBoth(config).map(_._1)

  private def without(key: String): String =
    generated.linesIterator.filterNot(_.trim.startsWith(key)).mkString("\n")

  private def withValue(key: String, value: String): String =
    generated.linesIterator.map(line => if line.trim.startsWith(key) then s"  $key = $value" else line).mkString("\n")

  def spec = suite("auth's audit config")(
    test("the block gen-env writes parses into the settings the writer runs with") {
      for both <- loadBoth(generated)
      yield assertTrue(
        both._1 == AuditEvents.Settings(retention = 7.days, overflow = AuditEvents.Overflow.block, blockTimeout = 2.seconds),
        both._2 == AuthAudit.Tiers(tokenRefresh = true, authorizeSuccess = true, parSuccess = true),
      )
    },
    test("a config written before the block existed makes auth refuse to start, naming the key") {
      for result <- load("par { request-uri-ttl = \"60 seconds\" }").either
      yield assertTrue(result.left.exists(_.toString.contains("audit")))
    },
    test("every key is required: leaving one out names it") {
      for
        retention <- load(without("retention")).either
        overflow <- load(without("overflow")).either
        timeout <- load(without("block-timeout")).either
        tokenRefresh <- load(without("token-refresh")).either
        authorize <- load(without("authorize-success")).either
        par <- load(without("par-success")).either
      yield assertTrue(
        retention.left.exists(_.toString.contains("retention")),
        overflow.left.exists(_.toString.contains("overflow")),
        timeout.left.exists(_.toString.contains("block-timeout")),
        tokenRefresh.left.exists(_.toString.contains("token-refresh")),
        authorize.left.exists(_.toString.contains("authorize-success")),
        par.left.exists(_.toString.contains("par-success")),
      )
    },
    test("overflow is block or drop, and drop is read") {
      for
        drop <- load(withValue("overflow", "\"drop\""))
        bad <- load(withValue("overflow", "\"wait\"")).either
      yield assertTrue(
        drop.overflow == AuditEvents.Overflow.drop,
        bad.left.exists(_.toString.contains("audit.overflow")),
      )
    },
    test("a retention the partitions cannot keep stops the start: under a day, or over the 14 days the table is kept ahead for") {
      for
        zero <- load(withValue("retention", "\"0 days\"")).either
        long <- load(withValue("retention", "\"15 days\"")).either
        max <- load(withValue("retention", "\"14 days\""))
      yield assertTrue(
        zero.left.exists(_.toString.contains("audit.retention")),
        long.left.exists(_.toString.contains("audit.retention")),
        max.retention == 14.days,
      )
    },
    test("a block timeout that is not positive stops the start") {
      for result <- load(withValue("block-timeout", "\"0 seconds\"")).either
      yield assertTrue(result.left.exists(_.toString.contains("audit.block-timeout")))
    },
    test("the high-volume kinds can be turned off one by one") {
      for both <- loadBoth(
          withValue("token-refresh", "false").linesIterator
            .map(line => if line.trim.startsWith("par-success") then "    par-success = false" else line).mkString("\n"),
        )
      yield assertTrue(!both._2.tokenRefresh, both._2.authorizeSuccess, !both._2.parSuccess)
    },
  ) @@ TestAspect.sequential
