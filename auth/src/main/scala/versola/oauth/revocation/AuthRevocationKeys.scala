package versola.oauth.revocation

import versola.oauth.model.AccessTokenPayload
import versola.util.revocation.RevocationKey

object AuthRevocationKeys:
  /** Everything that could have revoked this token, widening as it goes: the token itself, the
    * refresh chain that issued it, its session, and its user.
    */
  def of(token: AccessTokenPayload): List[RevocationKey] =
    RevocationKey.Jti(token.id.encoded) ::
      token.familyId.map(RevocationKey.Fam(_)).toList :::
      token.sessionId.map(RevocationKey.Sid(_)).toList :::
      List(RevocationKey.Sub(token.subject))
