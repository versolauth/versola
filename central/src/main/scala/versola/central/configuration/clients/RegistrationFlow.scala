package versola.central.configuration.clients

import versola.central.configuration.roles.RoleId
import zio.json.{JsonCodec, jsonDiscriminator, jsonHint}
import zio.prelude.Equal
import zio.schema.*
import zio.schema.annotation.{caseName, discriminatorName}

/** A step the user passes to create an account. New kinds of registration (KYC, invite
  * codes, consent screens) are added as further variants; each carries its own settings.
  */
@jsonDiscriminator("type")
@discriminatorName("type")
sealed trait RegistrationStep derives Schema, JsonCodec, Equal

object RegistrationStep:
  /** Proves ownership of the entry credential (phone/email) with a one-time code. */
  @jsonHint("otp")
  @caseName("otp")
  case class Otp() extends RegistrationStep derives Schema, JsonCodec, Equal

  /** Asks the new user to choose a password. */
  @jsonHint("setPassword")
  @caseName("setPassword")
  case class SetPassword() extends RegistrationStep derives Schema, JsonCodec, Equal

  /** Asks the new user to enroll a passkey. */
  @jsonHint("passkeyEnroll")
  @caseName("passkeyEnroll")
  case class PasskeyEnroll() extends RegistrationStep derives Schema, JsonCodec, Equal

/** Self-service account creation for a client. Enabled only alongside an [[AuthFlow]]
  * whose primary credentials are phone/email; the entry credential is the one the user
  * already typed on the credential card, so it is not configured separately here.
  *
  * @param steps ordered steps the user passes before the account is created
  * @param roleId role granted to the account on creation
  */
case class RegistrationFlow(
    steps: List[RegistrationStep],
    roleId: RoleId,
) derives Schema, JsonCodec, Equal

object RegistrationFlow:
  /** Seeded by bootstrap with no permissions; the default for new registrations. */
  val defaultRoleId: RoleId = RoleId("user")

  val default: RegistrationFlow = RegistrationFlow(
    steps = List(RegistrationStep.Otp()),
    roleId = defaultRoleId,
  )
