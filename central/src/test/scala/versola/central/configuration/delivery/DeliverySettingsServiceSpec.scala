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

    /** Built through `live`, so the first load, `sync()` and the at-rest key are the real ones. */
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
          _ <- service.upsertSettings(
            DeliverySettingsService.Update(smtp = Some(smtp), smtpPassword = Some(plaintext), otpProvider = None, otpProviderPassword = None),
          )
          stored = env.repository.upsert.calls.head
          storedPassword <- ZIO.fromOption(stored.smtpPassword).orElseFail(RuntimeException("no password stored"))
          roundTrip <- decrypted(security, storedPassword)
        yield assertTrue(
          stored.smtp == Some(smtp),
          stored.otpProvider.isEmpty,
          stored.otpProviderPassword.isEmpty,
          roundTrip == plaintext,
          !storedPassword.toSeq.containsSlice(plaintext.getBytes(StandardCharsets.UTF_8).toSeq),
        )
      },
      test("keeps the stored password as it is when the update leaves it out") {
        val env = Env()
        for
          (service, security) <- {
            // The first load happens inside `live`, so the stub answers before the service exists.
            for
              _ <- env.repository.getAll.succeedsWith(None)
              pair <- env.service
            yield pair
          }
          onRecord <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(
            Some(DeliverySettingsRecord(smtp = Some(smtp), smtpPassword = Some(onRecord), otpProvider = None, otpProviderPassword = None)),
          )
          _ <- env.repository.upsert.succeedsWith(())
          _ <- service.upsertSettings(
            DeliverySettingsService.Update(smtp = Some(smtp.copy(subject = "New subject")), smtpPassword = None, otpProvider = None, otpProviderPassword = None),
          )
          stored = env.repository.upsert.calls.head
        yield assertTrue(
          stored.smtp.map(_.subject) == Some("New subject"),
          stored.smtpPassword.map(_.toSeq) == Some(onRecord.toSeq),
        )
      },
      test("clears the stored password when the update sends an empty one") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          (service, security) <- env.service
          onRecord <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(
            Some(DeliverySettingsRecord(smtp = Some(smtp), smtpPassword = Some(onRecord), otpProvider = None, otpProviderPassword = None)),
          )
          _ <- env.repository.upsert.succeedsWith(())
          _ <- service.upsertSettings(
            DeliverySettingsService.Update(smtp = Some(smtp), smtpPassword = Some(""), otpProvider = None, otpProviderPassword = None),
          )
        yield assertTrue(env.repository.upsert.calls.head.smtpPassword.isEmpty)
      },
      test("drops a block's password together with the block that is left out") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(None)
          (service, security) <- env.service
          onRecord <- encrypted(security, plaintext)
          _ <- env.repository.getAll.succeedsWith(
            Some(DeliverySettingsRecord(smtp = None, smtpPassword = None, otpProvider = Some(otpProvider), otpProviderPassword = Some(onRecord))),
          )
          _ <- env.repository.upsert.succeedsWith(())
          _ <- service.upsertSettings(
            DeliverySettingsService.Update(smtp = Some(smtp), smtpPassword = None, otpProvider = None, otpProviderPassword = None),
          )
          stored = env.repository.upsert.calls.head
        yield assertTrue(stored.otpProvider.isEmpty, stored.otpProviderPassword.isEmpty, stored.smtpPassword.isEmpty)
      },
      test("refuses an invalid block and writes nothing") {
        val env = Env()
        val invalid = List(
          DeliverySettingsService.Update(smtp = Some(smtp.copy(from = "not-an-address")), smtpPassword = None, otpProvider = None, otpProviderPassword = None),
          DeliverySettingsService.Update(smtp = Some(smtp.copy(port = 70000)), smtpPassword = None, otpProvider = None, otpProviderPassword = None),
          DeliverySettingsService.Update(smtp = Some(smtp.copy(host = " ")), smtpPassword = None, otpProvider = None, otpProviderPassword = None),
          DeliverySettingsService.Update(smtp = None, smtpPassword = None, otpProvider = Some(otpProvider.copy(method = "DELETE")), otpProviderPassword = None),
          DeliverySettingsService.Update(smtp = None, smtpPassword = None, otpProvider = Some(otpProvider.copy(url = "sms.example.com/send")), otpProviderPassword = None),
          DeliverySettingsService.Update(smtp = None, smtpPassword = None, otpProvider = Some(otpProvider.copy(body = Map("" -> "x"))), otpProviderPassword = None),
        )
        for
          _ <- env.repository.getAll.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(())
          (service, _) <- env.service
          exits <- ZIO.foreach(invalid)(update => service.upsertSettings(update).exit)
        yield assertTrue(
          exits.forall(_.causeOption.exists(_.failures.exists(_.isInstanceOf[DeliverySettingsService.ValidationError]))),
          env.repository.upsert.calls.isEmpty,
        )
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
            Some(DeliverySettingsRecord(smtp = Some(smtp), smtpPassword = Some(onRecord), otpProvider = Some(otpProvider), otpProviderPassword = None)),
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
