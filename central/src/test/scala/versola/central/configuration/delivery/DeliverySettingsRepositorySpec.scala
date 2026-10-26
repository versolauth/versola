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
  private val newSmtpPassword = Secret(Array[Byte](5, 6, 7, 8))
  private val otpProviderPassword = Secret(Array[Byte](9, 8, 7))

  private val both = DeliverySettingsRepository.Write(
    smtp = Some(smtp),
    smtpPassword = DeliverySettingsRepository.StoredPassword.Set(smtpPassword),
    otpProvider = Some(otpProvider),
    otpProviderPassword = DeliverySettingsRepository.StoredPassword.Set(otpProviderPassword),
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
          _ <- env.repository.upsert(both)
          result <- env.repository.getAll
        yield assertTrue(
          result.flatMap(_.smtp) == Some(smtp),
          result.flatMap(_.otpProvider) == Some(otpProvider),
          bytes(result) == (Some(smtpPassword.toSeq), Some(otpProviderPassword.toSeq)),
        )
      },
      test("Keep leaves the stored password as it is while the rest of the block changes") {
        for
          _ <- env.repository.upsert(both)
          _ <- env.repository.upsert(both.copy(
            smtp = Some(smtp.copy(subject = "Changed")),
            smtpPassword = DeliverySettingsRepository.StoredPassword.Keep,
          ))
          result <- env.repository.getAll
        yield assertTrue(
          result.flatMap(_.smtp).map(_.subject) == Some("Changed"),
          bytes(result) == (Some(smtpPassword.toSeq), Some(otpProviderPassword.toSeq)),
        )
      },
      test("Keep on the first save stores no password") {
        for
          _ <- env.repository.upsert(both.copy(
            smtpPassword = DeliverySettingsRepository.StoredPassword.Keep,
            otpProviderPassword = DeliverySettingsRepository.StoredPassword.Keep,
          ))
          result <- env.repository.getAll
        yield assertTrue(result.isDefined, bytes(result) == (None, None))
      },
      test("Set replaces the stored password and Clear removes it") {
        for
          _ <- env.repository.upsert(both)
          _ <- env.repository.upsert(both.copy(
            smtpPassword = DeliverySettingsRepository.StoredPassword.Set(newSmtpPassword),
            otpProviderPassword = DeliverySettingsRepository.StoredPassword.Clear,
          ))
          result <- env.repository.getAll
        yield assertTrue(bytes(result) == (Some(newSmtpPassword.toSeq), None))
      },
      test("a block that is left out is removed together with its Cleared password") {
        for
          _ <- env.repository.upsert(both)
          _ <- env.repository.upsert(both.copy(otpProvider = None, otpProviderPassword = DeliverySettingsRepository.StoredPassword.Clear))
          result <- env.repository.getAll
        yield assertTrue(result.flatMap(_.otpProvider).isEmpty, bytes(result) == (Some(smtpPassword.toSeq), None))
      },
      test("a record with both blocks empty is still stored, so getAll tells it from nothing stored") {
        val empty = DeliverySettingsRepository.Write(
          smtp = None,
          smtpPassword = DeliverySettingsRepository.StoredPassword.Clear,
          otpProvider = None,
          otpProviderPassword = DeliverySettingsRepository.StoredPassword.Clear,
        )
        for
          _ <- env.repository.upsert(empty)
          result <- env.repository.getAll
        yield assertTrue(result.isDefined, result.flatMap(_.smtp).isEmpty, result.flatMap(_.otpProvider).isEmpty)
      },
      test("delete returns getAll to None, and deleting nothing is not an error") {
        for
          _ <- env.repository.delete()
          _ <- env.repository.upsert(both)
          _ <- env.repository.delete()
          result <- env.repository.getAll
        yield assertTrue(result.isEmpty)
      },
    )

object DeliverySettingsRepositorySpec:
  case class Env(repository: DeliverySettingsRepository)
