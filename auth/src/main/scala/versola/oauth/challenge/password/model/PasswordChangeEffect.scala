package versola.oauth.challenge.password.model

import versola.oauth.revocation.RevocationTerms
import versola.oauth.session.model.PublicSessionId

/** What a permanent or temporary password being stored does to what the user already holds. Who changes
  * the password decides it, and the repository applies it in the transaction that stores the password, so
  * the password and the refusal of the tokens issued under the old one commit together or not at all.
  */
enum PasswordChangeEffect:
  /** The password and nothing else: a user setting their first password in the sign-in flow, the bootstrap
    * administrator. */
  case Untouched

  /** An administrator set or reset the password. Records `sub:<user>` bounded at the instant of the change,
    * so an access token issued before it is refused and one the user obtains afterwards is not. Sessions
    * and refresh tokens are left alone: the user refreshes, or signs in again, and gets a token that
    * postdates the boundary. */
  case RefuseEarlierTokens(terms: RevocationTerms)

  /** The user changed their own password and asked to be signed out everywhere else. Ends every other
    * session of the user with its refresh tokens and records `sid:` for each, so tokens issued under the
    * session that made the change stay valid. */
  case EndOtherSessions(keep: PublicSessionId, terms: RevocationTerms)
