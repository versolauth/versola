package versola.edge.revocation

import versola.edge.model.{AccessTokenId, RefreshTokenFamilyId, SessionId}
import versola.util.revocation.RevocationKey

object EdgeRevocationKeys:
  /** Everything that could have revoked the token these claims came from, widening as it
    * goes: the token itself, then the refresh chain that issued it, then the session it
    * belongs to, then its user.
    */
  def of(
      jti: AccessTokenId,
      family: Option[RefreshTokenFamilyId],
      sid: Option[SessionId],
      subject: String,
  ): List[RevocationKey] =
    RevocationKey.Jti(jti) :: family.map(RevocationKey.Fam(_)).toList :::
      sid.map(RevocationKey.Sid(_)).toList ::: List(RevocationKey.Sub(subject))
