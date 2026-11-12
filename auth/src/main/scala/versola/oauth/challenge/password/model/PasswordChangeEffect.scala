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

  /** An administrator set the password (the non-production `POST /users/password/set`). Records `sub:<user>`
    * for the change (see [[versola.util.revocation.Revocation.userBound]]), so an access token issued in an
    * earlier second is refused and one the user obtains in the same second or afterwards is not. Sessions and refresh tokens are left alone: the user refreshes, or signs in
    * again, and gets a token that postdates the boundary. */
  case RefuseEarlierTokens(terms: RevocationTerms)

  /** An administrator reset the password, which is what an administrator does when the account may be in
    * someone else's hands. Ends every session and every refresh token of the user and records `sub:<user>`
    * for the reset, as "sign out everywhere" does: a record alone would cost the
    * intruder one refresh, since a live refresh token mints a token that postdates the boundary. */
  case EndAllSessions(terms: RevocationTerms)

  /** The user changed their own password and asked to be signed out everywhere else. Ends every other
    * session of the user with its refresh tokens and records `sid:` for each, so tokens issued under the
    * session that made the change stay valid. */
  case EndOtherSessions(keep: PublicSessionId, terms: RevocationTerms)
