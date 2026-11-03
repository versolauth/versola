package versola.antifraud

/** The country signal: the country an OTP request's address is in.
  *
  * [[AntifraudEvents]] puts it into the context's `signals` under [[CountrySignal]] as an ISO 3166-1 alpha-2 code in upper
  * case (`KZ`), from the DB-IP country database (`antifraud.geo.file`); where that has no answer, from the country the
  * autonomous system is registered in, but only for a network that is not `hosting`, since the registration country of a
  * data centre or a VPN provider says where its owner is and nothing of where its customers are. It is absent without a
  * database and for an address no database knows. There is no rule on it yet: the one reader is the `country` list entry
  * ([[ListSnapshot]]), which an operator uses to deny, fake or watch a country. A signal nobody reads is not put into the
  * context for its own sake, so a rule that wants it (a mismatch with the number's country, say) reads this key.
  */
object GeoRules:

  /** The [[AntifraudContext.signals]] key of the country: an upper-case ISO alpha-2 code. */
  val CountrySignal = "country"
