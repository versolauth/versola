package versola.util

/** Which `aud` values this server accepts on a JWT a client signed for it -- an RFC 7523
  * client assertion or an RFC 9101 request object.
  *
  * The two cases are the two security profiles a tenant can be on: FAPI 2.0 narrows `aud` to
  * exactly one value, while plain OAuth lets clients name the server in several ways.
  */
enum JwtAudience:
  /** FAPI 2.0 Security Profile §5.3.2.1-8: the issuer identifier (RFC 8414) and nothing else,
    * carried as a JSON string. An array -- even one holding only the issuer -- and an
    * endpoint URL are both refused: each widens the set of servers a captured JWT could be
    * presented to, which is the audience confusion the rule exists to close. */
  case IssuerOnly(issuer: String)

  /** RFC 7523 §3 / RFC 9101 §4 as clients send it in the wild: the issuer identifier or an
    * endpoint URL, as a string or as an array naming at least one of them. */
  case AnyOf(accepted: Set[String])

  /** Whether an `aud` claim, already read as either a single string (`Left`) or an array
    * (`Right`), names this server under this policy. */
  def accepts(aud: Either[String, Set[String]]): Boolean = this match
    case IssuerOnly(issuer) => aud == Left(issuer)
    case AnyOf(accepted) => aud.fold(Set(_), identity).exists(accepted.contains)
