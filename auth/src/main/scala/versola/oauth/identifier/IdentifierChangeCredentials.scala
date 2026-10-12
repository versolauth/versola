package versola.oauth.identifier

import versola.util.{Email, Phone}

/** Parses the new identifier of a change, the one rule shared by the account page (which records
  * the intent) and `/authorize` (which reads it back from `login_hint`): both must arrive at the
  * same canonical value for the intent to match.
  */
object IdentifierChangeCredentials:

  /** `allowedPhonePrefixes` is the tenant's allow-list; empty allows every country. A phone must
    * be written in international form, as `login_hint` already requires.
    */
  def parse(
      kind: IdentifierChangeKind,
      raw: String,
      allowedPhonePrefixes: List[String],
  ): Option[Either[Email, Phone]] =
    kind match
      case IdentifierChangeKind.Phone =>
        Option.when(raw.startsWith("+") && raw.drop(1).forall(_.isDigit)):
          Phone.parse(raw).toOption
            .filter(phone => allowedPhonePrefixes.isEmpty || allowedPhonePrefixes.exists(phone.startsWith))
            .map(Right(_))
        .flatten
      case IdentifierChangeKind.Email =>
        Email.from(raw).toOption.map(Left(_))
