package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Client
import zio.test.*

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** A client's authentication method is fixed when it is created, the clients bootstrap seeds
  * included: a central started with configuration that calls for another method than a seeded
  * client holds refuses to start, and leaves the client as it was.
  *
  * Each test starts a second central from the staged build, on ports of its own, against the
  * database the running one uses, with the running one's `env.conf` overridden at the end to
  * call for the other method. It is refused in bootstrap, before it listens or processes
  * anything, so the running stack never sees it.
  */
object BootstrapAuthMethodSpec extends ZIOSpec[CentralApi & E2EConfig]:
  override val bootstrap: ZLayer[Any, Any, CentralApi & E2EConfig] =
    (E2EConfig.live ++ Client.default) >>> (CentralApi.live ++ ZLayer.service[E2EConfig])

  /** Starts central on `override` appended to the running one's configuration, and answers its
    * exit code and output -- `None` for a central still running when it should have stopped. */
  private def boot(
      config: E2EConfig,
      `override`: String,
      edit: String => String = identity,
  ): Task[(Option[Int], String)] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking((Files.createTempFile("e2e-central-", ".conf").nn, Files.createTempFile("e2e-central-", ".log").nn)),
    )((conf, log) => ZIO.attemptBlocking { Files.deleteIfExists(conf); Files.deleteIfExists(log) }.ignore): (conf, log) =>
      ZIO.attemptBlocking:
        Files.writeString(conf, edit(Files.readString(Path.of(config.centralEnvConf))) + "\n" + `override` + "\n")
        val builder = ProcessBuilder(config.centralLauncher, s"-Denv.path=$conf")
          .redirectErrorStream(true)
          .redirectOutput(log.toFile)
        builder.environment().nn.put("PORT", "9011")
        builder.environment().nn.put("DPORT", "9012")
        builder.environment().nn.put("RUN_MIGRATIONS", "false")
        val process = builder.start().nn
        val exited = process.waitFor(2, TimeUnit.MINUTES)
        if !exited then process.destroyForcibly()
        (Option.when(exited)(process.exitValue()), Files.readString(log))

  private def authMethodOf(central: CentralApi, clientId: String): Task[Option[String]] =
    central.get("/configuration/clients", "tenantId" -> Fixtures.defaultTenant)
      .flatMap(_.items("clients"))
      .map(_.find(_.str("id").contains(clientId)).flatMap(_.str("authMethod")))

  def spec = suite("Bootstrap: a seeded client's authentication method")(
    test("central refuses to start when central-admin's certificate is taken out of its configuration") {
      for
        central <- ZIO.service[CentralApi]
        config <- ZIO.service[E2EConfig]
        before <- authMethodOf(central, "central-admin")
        (exit, output) <- boot(config, "bootstrap.central-admin-mtls = null")
        after <- authMethodOf(central, "central-admin")
      yield assertTrue(before.contains("tls_client_auth"))
        .label("the stack must have seeded central-admin with its certificate for this to mean anything") &&
        assertTrue(exit.exists(_ != 0)).label("a central whose configuration contradicts a seeded client must not start") &&
        assertTrue(output.contains(
          "'central-admin' is registered with tls_client_auth, but bootstrap.central-admin-mtls calls for client_secret",
        )).label("the refusal must name the client, both methods and the setting to restore") &&
        assertTrue(after == before).label("the refused boot must leave the client as it was")
    },
    test("central refuses to start when utils is given a secret in place of the key it was created with") {
      for
        central <- ZIO.service[CentralApi]
        config <- ZIO.service[E2EConfig]
        before <- authMethodOf(central, config.provisionerClientId)
        (exit, output) <- boot(
          config,
          "bootstrap.utility-client.secret = \"e2e-bootstrap-probe\"",
          // Removed from the text, not set to `null`: a `null` where an `Option[Json.Obj]` is read
          // fails central's configuration decoding, which it then reports with an unbounded
          // cause that exhausts the heap instead of the refusal this test expects.
          edit = _.replaceFirst("""(?s)(utility-client \{.*?)\n\s*public-key-jwk = [^\n]*""", "$1"),
        )
        after <- authMethodOf(central, config.provisionerClientId)
      yield assertTrue(before.contains("private_key_jwt"))
        .label("the stack must have seeded utils with its key for this to mean anything") &&
        assertTrue(exit.exists(_ != 0)).label("a central whose configuration contradicts a seeded client must not start") &&
        assertTrue(output.contains(
          s"'${config.provisionerClientId}' is registered with private_key_jwt, but bootstrap.utility-client calls for client_secret",
        )).label("the refusal must name the client, both methods and the setting to restore") &&
        assertTrue(after == before).label("the refused boot must leave the client as it was")
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
