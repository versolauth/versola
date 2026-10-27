package versola.central.configuration.delivery

import versola.central.TestCentralConfig
import versola.util.{Secret, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

object DeliverySettingsServiceSpec extends UnitSpecBase:
  private val config = TestCentralConfig.config
  private val atRestKey = SecretKeySpec(config.clientSecretsSecret, "AES")

  private val smtp = SmtpDeliverySettings(
    host = "smtp.example.com",
    port = 587,
    username = "mailer",
    from = "noreply@example.com",
    subject = "Your code",
    startTls = true,
    enabled = true,
  )

  private val otpProvider = OtpProviderDeliverySettings(
    method = "POST",
    url = "https://sms.example.com/send",
    username = Some("sms"),
    body = Map("phones" -> "{{phone}}", "mes" -> "{{message}}"),
    enabled = true,
  )

  private val plaintext = "s3cret-password"

  private val securityLayer: ULayer[SecurityService] = ZLayer.make[SecurityService](SecureRandom.live, SecurityService.live)

  class Env:
    val repository = stub[DeliverySettingsRepository]

    /** Built through `live`, so the first load, `sync()` and the at-rest key are the real ones.
      * The first load happens inside `live`, so the repository stub answers before this is called.
      */
    def service: ZIO[Scope, Throwable, (DeliverySettingsService, SecurityService)] =
      (for
        service <- ZIO.service[DeliverySettingsService]
        security <- ZIO.service[SecurityService]
      yield (service, security)).provideSome[Scope](
        ZLayer.succeed[DeliverySettingsRepository](repository),
        securityLayer,
        ZLayer.succeed(config),
        DeliverySettingsService.live,
      )

  private def update(
      smtp: Option[SmtpDeliverySettings] = None,
      smtpPassword: Option[String] = None,
      otpProvider: Option[OtpProviderDeliverySettings] = None,
      otpProviderPassword: Option[String] = None,
  ): DeliverySettingsService.Update =
    DeliverySettingsService.Update(
      smtp = smtp,
      smtpPassword = smtpPassword,
      otpProvider = otpProvider,
      otpProviderPassword = otpProviderPassword,
    )

  private def encrypted(security: SecurityService, value: String): Task[Secret] =
    security.encryptAes256(Secret.fromString(value), atRestKey).map(Secret(_))

  private def decrypted(security: SecurityService, secret: Secret): Task[String] =
    security.decryptAes256(secret, atRestKey).map(bytes => String(bytes, StandardCharsets.UTF_8))

  def spec = suite("DeliverySettingsService")(
    suite("upsertSettings")(
      test("stores a new password encrypted under clientSecretsSecret, never the plaintext") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          (service, security) <- env.service
          _ <- service.upsertSettings(update(smtp = Some(smtp), smtpPassword = Some(plaintext)))
          written = env.repository.upsert.calls.head
          ciphertext <- written.smtpPassword match
            case DeliverySettingsRepository.StoredPassword.Set(ciphertext) => ZIO.succeed(ciphertext)
            case other => ZIO.fail(RuntimeException(s"expected a new password, got $other"))
          roundTrip <- decrypted(security, ciphertext)
        yield assertTrue(
          written.smtp == Some(smtp),
          written.otpProvider.isEmpty,
          written.otpProviderPassword == DeliverySettingsRepository.StoredPassword.Clear,
          roundTrip == plaintext,
          !ciphertext.toSeq.containsSlice(plaintext.getBytes(StandardCharsets.UTF_8).toSeq),
        )
      },
      test("asks the repository to keep the stored password when the update leaves it out") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          _ <- service.upsertSettings(update(smtp = Some(smtp.copy(subject = "New subject"))))
          written = env.repository.upsert.calls.head
        yield assertTrue(
          written.smtp.map(_.subject) == Some("New subject"),
          written.smtpPassword == DeliverySettingsRepository.StoredPassword.Keep,
        )
      },
      test("clears the stored password when the update sends an empty one") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          _ <- service.upsertSettings(update(smtp = Some(smtp), smtpPassword = Some("")))
        yield assertTrue(env.repository.upsert.calls.head.smtpPassword == DeliverySettingsRepository.StoredPassword.Clear)
      },
      test("drops a block's password together with the block that is left out, whatever the update says") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          _ <- service.upsertSettings(update(smtp = Some(smtp), otpProviderPassword = Some("orphan")))
          written = env.repository.upsert.calls.head
        yield assertTrue(written.otpProvider.isEmpty, written.otpProviderPassword == DeliverySettingsRepository.StoredPassword.Clear)
      },
      test("serves its own write at once, without waiting for the change notification") {
        val env = Env()
        val saved = DeliverySettingsRecord(smtp = Some(smtp), smtpPassword = None, otpProvider = None, otpProviderPassword = None)
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          before <- service.getSettings
          _ <- env.repository.getAll.succeedsWith(Some(saved))
          _ <- service.upsertSettings(update(smtp = Some(smtp)))
          after <- service.getSettings
        yield assertTrue(before.isEmpty, after.flatMap(_.smtp) == Some(smtp))
      },
      test("still succeeds when reading the record back fails after the write") {
        for
          reads <- Ref.make(0)
          writes <- Ref.make(0)
          // The first read is the cache's own load, the second is the check on a kept password, the third the read-back.
          repository = new DeliverySettingsRepository:
            override def getAll: Task[Option[DeliverySettingsRecord]] =
              reads.updateAndGet(_ + 1).flatMap(n => if n > 2 then ZIO.fail(RuntimeException("database unreachable")) else ZIO.none)
            override def upsert(write: DeliverySettingsRepository.Write): Task[Unit] = writes.update(_ + 1)
            override def delete(): Task[Unit] = ZIO.unit
          service <- ZIO.service[DeliverySettingsService].provideSome[Scope](
            ZLayer.succeed[DeliverySettingsRepository](repository),
            securityLayer,
            ZLayer.succeed(config),
            DeliverySettingsService.live,
          )
          result <- service.upsertSettings(update(smtp = Some(smtp)))
          written <- writes.get
        yield assertTrue(result == Right(()), written == 1)
      },
      test("refuses to keep a saved password when the SMTP server, port or account changes, and writes nothing") {
        val env = Env()
        for
          security <- ZIO.service[SecurityService].provideLayer(securityLayer)
          saved <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(Some(DeliverySettingsRecord(Some(smtp), Some(saved), None, None)))
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          results <- ZIO.foreach(List(
            smtp.copy(host = "smtp.attacker.example"),
            smtp.copy(port = 2525),
            smtp.copy(username = "someone-else"),
          ))(block => service.upsertSettings(update(smtp = Some(block))))
        yield assertTrue(
          results.map(
            _.left.map(_.message),
          ) == List.fill(3)(Left("smtp.password must be entered again when the server address, port or username changes")),
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("keeps a saved SMTP password for the same server, and takes a new or an empty one for another") {
        val env = Env()
        for
          security <- ZIO.service[SecurityService].provideLayer(securityLayer)
          saved <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(Some(DeliverySettingsRecord(Some(smtp), Some(saved), None, None)))
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          sameServer <- service.upsertSettings(update(smtp = Some(smtp.copy(subject = "New subject"))))
          withNewPassword <- service.upsertSettings(update(smtp = Some(smtp.copy(host = "smtp.other.example")), smtpPassword = Some("new")))
          withClearedPassword <- service.upsertSettings(update(smtp = Some(smtp.copy(host = "smtp.other.example")), smtpPassword = Some("")))
        yield assertTrue(sameServer.isRight, withNewPassword.isRight, withClearedPassword.isRight, env.repository.upsert.calls.length == 3)
      },
      test("has nothing to protect when no password is saved, so another server is accepted without one") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(Some(DeliverySettingsRecord(Some(smtp), None, Some(otpProvider), None)))
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          result <- service.upsertSettings(update(
            smtp = Some(smtp.copy(host = "smtp.other.example")),
            otpProvider = Some(otpProvider.copy(url = "https://sms.other.example/send")),
          ))
        yield assertTrue(result.isRight)
      },
      test("refuses to keep a saved SMS password when the address changes to another host or account, but not for another path") {
        val env = Env()
        for
          security <- ZIO.service[SecurityService].provideLayer(securityLayer)
          saved <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(Some(DeliverySettingsRecord(None, None, Some(otpProvider), Some(saved))))
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          otherHost <- service.upsertSettings(update(otpProvider = Some(otpProvider.copy(url = "https://sms.attacker.example/send"))))
          otherAccount <- service.upsertSettings(update(otpProvider = Some(otpProvider.copy(username = Some("someone-else")))))
          otherPath <- service.upsertSettings(update(otpProvider = Some(otpProvider.copy(url = "https://sms.example.com/v2/send"))))
        yield assertTrue(
          otherHost.left.map(_.message.takeWhile(_ != ' ')) == Left("otpProvider.password"),
          otherAccount.left.map(_.message.takeWhile(_ != ' ')) == Left("otpProvider.password"),
          otherPath.isRight,
          env.repository.upsert.calls.length == 1,
        )
      },
      test("refuses to keep a saved password when STARTTLS is switched off, and writes nothing") {
        val env = Env()
        for
          security <- ZIO.service[SecurityService].provideLayer(securityLayer)
          saved <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(Some(DeliverySettingsRecord(Some(smtp), Some(saved), None, None)))
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          result <- service.upsertSettings(update(smtp = Some(smtp.copy(startTls = false))))
        yield assertTrue(result.isLeft, env.repository.upsert.calls.isEmpty)
      },
      test("does not print the passwords of an update") {
        val printed = update(smtp = Some(smtp), smtpPassword = Some(plaintext), otpProviderPassword = Some("sms-s3cret")).toString
        assertTrue(!printed.contains(plaintext), !printed.contains("sms-s3cret"))
      },
      test("refuses an invalid block and writes nothing") {
        val env = Env()
        val invalid = List(
          update(smtp = Some(smtp.copy(from = "not-an-address"))),
          update(otpProvider = Some(otpProvider.copy(url = "https://sms:topsecret@sms.example.com/send"))),
        )
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          results <- ZIO.foreach(invalid)(service.upsertSettings)
        yield assertTrue(
          results.map(_.left.map(_.message.takeWhile(_ != ' '))) == List(Left("smtp.from"), Left("otpProvider.url")),
          env.repository.upsert.calls.isEmpty,
        )
      },
    ),
    suite("deleteSettings")(
      test("removes the record through the repository") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- (() => env.repository.delete()).succeedsWith(())
          (service, _) <- env.service
          _ <- service.deleteSettings()
        yield assertTrue((() => env.repository.delete()).calls.length == 1)
      },
      test("serves the empty record at once after the removal") {
        val env = Env()
        val saved = DeliverySettingsRecord(smtp = Some(smtp), smtpPassword = None, otpProvider = None, otpProviderPassword = None)
        for
          _ <- env.repository.getAll.succeedsWith(Some(saved))
          _ <- (() => env.repository.delete()).succeedsWith(())
          (service, _) <- env.service
          before <- service.getSettings
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- service.deleteSettings()
          after <- service.getSettings
        yield assertTrue(before.flatMap(_.smtp) == Some(smtp), after.isEmpty)
      },
    ),
    suite("getSettings")(
      test("serves the record with its passwords decrypted, on the first load and after sync") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          (service, security) <- env.service
          before <- service.getSettings
          onRecord <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(
            Some(DeliverySettingsRecord(
              smtp = Some(smtp),
              smtpPassword = Some(onRecord),
              otpProvider = Some(otpProvider),
              otpProviderPassword = None,
            )),
          )
          _ <- service.sync()
          after <- service.getSettings
        yield assertTrue(
          before.isEmpty,
          after.flatMap(_.smtp) == Some(smtp),
          after.flatMap(_.smtpPassword).map(s => String(s, StandardCharsets.UTF_8)) == Some(plaintext),
          after.flatMap(_.otpProviderPassword).isEmpty,
        )
      },
    ),
  )
