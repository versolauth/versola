package versola.central.configuration.delivery

import versola.util.{CacheSource, Secret}
import zio.Task

/** Stores the record with its passwords as the service hands them over, already encrypted. */
trait DeliverySettingsRepository extends CacheSource[Option[DeliverySettingsRecord]]:
  /** `None` until the first save, and again after [[delete]]. */
  def getAll: Task[Option[DeliverySettingsRecord]]

  /** Stores both blocks and applies each [[DeliverySettingsRepository.StoredPassword]] in the same
    * statement, so a password kept across a save is never read into the service and written back:
    * two saves racing each other cannot put an old ciphertext over a new one. The password is
    * applied as sent, whether or not its block is present: a block that is left out is the
    * caller's to send with `Clear`, which [[DeliverySettingsService]] does.
    */
  def upsert(write: DeliverySettingsRepository.Write): Task[Unit]

  /** Removes the record: the deployment is back to the `auth.conf` blocks, as if nothing had been saved. */
  def delete(): Task[Unit]

object DeliverySettingsRepository:

  /** What a save does to a block's stored password. */
  enum StoredPassword:
    case Keep
    case Clear
    case Set(ciphertext: Secret)

  case class Write(
      smtp: Option[SmtpDeliverySettings],
      smtpPassword: StoredPassword,
      otpProvider: Option[OtpProviderDeliverySettings],
      otpProviderPassword: StoredPassword,
  )
