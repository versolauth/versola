package versola.antifraud

/** The country signal: the country an OTP request's address is in.
  *
  * [[AntifraudEvents]] puts it into the context's `signals` under [[CountrySignal]] as an ISO 3166-1 alpha-2 code in upper
  * case (`KZ`), from the DB-IP country database (`antifraud.geo.file`) and from nothing else. It is absent without that
  * database, whatever the ASN database knows, and for an address the database has no country for. The country an AS is
  * registered in ([[versola.util.net.AsnInfo.country]]) is deliberately not a fallback: a registry's record says where the
  * holder is, not where its subscribers are (a satellite ISP registered in one country serves the world), and under one name
  * an operator could not tell a geolocated address from a registration, nor know what a `country` deny acts on. There is
  * no rule on it yet: the one reader is the `country` list entry
  * ([[ListSnapshot]]), which an operator uses to deny, fake or watch a country. A signal nobody reads is not put into the
  * context for its own sake, so a rule that wants it (a mismatch with the number's country, say) reads this key.
  */
object GeoRules:

  /** The [[AntifraudContext.signals]] key of the country: an upper-case ISO alpha-2 code. */
  val CountrySignal = "country"
