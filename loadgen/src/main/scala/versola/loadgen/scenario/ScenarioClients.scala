package versola.loadgen.scenario

import versola.loadgen.model.CredentialKind
import versola.loadgen.protocol.PresetId

/** Which provisioned client a virtual user authenticates through, and the one edge preset the web
  * path logs in from (design doc §2.2, dev spec §8.1-8.4).
  *
  * A value rather than a lookup in [[versola.loadgen.provision.CampaignBlueprint]], even though
  * the four ids are that object's constants: `provision` is a role a driver never runs, and its
  * config block is absent from a driver's file entirely
  * ([[versola.loadgen.config.ProvisionConfig]]), so a driver importing the blueprint would be
  * importing the campaign's *intended* configuration in place of the one it was actually pointed
  * at. Constructed once at driver boot, from whatever named the clients that run.
  *
  * `scope` is one string for all four because it is: `CampaignBlueprint.scopes` registers the
  * same set on every client, and `offline_access` in particular is what makes §2.3's 96.7%
  * refresh path exist at all.
  */
final case class ScenarioClients(
    mobileOtp: String,
    mobileOtpPassword: String,
    mobilePasskey: String,
    webPreset: PresetId,
    scope: String,
):
  /** The client id a mobile login uses, chosen by the credential the user actually holds -- which
    * is what makes §8.1, §8.2 and §8.3 three flows rather than three transcripts of one.
    */
  def mobileClientFor(credential: CredentialKind): String =
    credential match
      case CredentialKind.Otp => mobileOtp
      case CredentialKind.OtpPassword => mobileOtpPassword
      case CredentialKind.Passkey => mobilePasskey
