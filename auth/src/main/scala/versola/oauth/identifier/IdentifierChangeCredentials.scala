package versola.oauth.identifier

import versola.oauth.client.model.PhoneRegion
import versola.util.{Email, Phone}

/** Parses the new identifier of a change, the one rule shared by the account page (which records
  * the intent) and `/authorize` (which reads it back from `login_hint`): both must arrive at the
  * same canonical value for the intent to match.
  */
object IdentifierChangeCredentials:

  /** `phoneRegions` are the countries the tenant takes; none allows every country. A phone is only
    * validated here, not normalised: the page writes it in E.164, and one that is valid but written
    * any other way is refused, so the intent and `login_hint` can only meet at one spelling.
    */
  def parse(
      kind: IdentifierChangeKind,
      raw: String,
      phoneRegions: List[PhoneRegion],
  ): Option[Either[Email, Phone]] =
    kind match
      case IdentifierChangeKind.Phone =>
        Option.when(raw.startsWith("+") && raw.drop(1).forall(_.isDigit)):
          Phone.validate(raw).toOption
            .filter(phone => PhoneRegion.allows(phoneRegions, phone))
            .map(Right(_))
        .flatten
      case IdentifierChangeKind.Email =>
        Email.from(raw).toOption.map(Left(_))
