package versola.central.configuration.delivery

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.{DatabaseSpecBase, Secret}
import zio.test.*

/** Conformance suite for any [[DeliverySettingsRepository]] implementation; see
  * `PostgresDeliverySettingsRepositorySpec` in `central-postgres-impl` for the one binding.
  */
trait DeliverySettingsRepositorySpec extends DatabaseSpecBase[DeliverySettingsRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

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
    enabled = false,
  )

  // What the service stores: ciphertext, opaque to the repository.
  private val smtpPassword = Secret(Array[Byte](1, 2, 3, 4))
  private val otpProviderPassword = Secret(Array[Byte](9, 8, 7))

  private val record = DeliverySettingsRecord(
    smtp = Some(smtp),
    smtpPassword = Some(smtpPassword),
    otpProvider = Some(otpProvider),
    otpProviderPassword = Some(otpProviderPassword),
  )

  private def bytes(record: Option[DeliverySettingsRecord]): (Option[Seq[Byte]], Option[Seq[Byte]]) =
    (record.flatMap(_.smtpPassword).map(_.toSeq), record.flatMap(_.otpProviderPassword).map(_.toSeq))

  override def testCases(env: DeliverySettingsRepositorySpec.Env) =
    List(
      test("getAll is None until something is stored") {
        for result <- env.repository.getAll
        yield assertTrue(result.isEmpty)
      },
      test("upsert then getAll returns both blocks and their passwords as stored") {
        for
          _ <- env.repository.upsert(record)
          result <- env.repository.getAll
        yield assertTrue(
          result.flatMap(_.smtp) == Some(smtp),
          result.flatMap(_.otpProvider) == Some(otpProvider),
          bytes(result) == (Some(smtpPassword.toSeq), Some(otpProviderPassword.toSeq)),
        )
      },
      test("upsert overwrites the stored record, clearing a block that is now absent") {
        val updated = DeliverySettingsRecord(
          smtp = Some(smtp.copy(enabled = false)),
          smtpPassword = None,
          otpProvider = None,
          otpProviderPassword = None,
        )
        for
          _ <- env.repository.upsert(record)
          _ <- env.repository.upsert(updated)
          result <- env.repository.getAll
        yield assertTrue(
          result.flatMap(_.smtp) == Some(smtp.copy(enabled = false)),
          result.flatMap(_.otpProvider).isEmpty,
          bytes(result) == (None, None),
        )
      },
      test("a record with both blocks empty is still stored, so getAll tells it from nothing stored") {
        val empty = DeliverySettingsRecord(smtp = None, smtpPassword = None, otpProvider = None, otpProviderPassword = None)
        for
          _ <- env.repository.upsert(empty)
          result <- env.repository.getAll
        yield assertTrue(result.isDefined, result.flatMap(_.smtp).isEmpty, result.flatMap(_.otpProvider).isEmpty)
      },
    )

object DeliverySettingsRepositorySpec:
  case class Env(repository: DeliverySettingsRepository)
