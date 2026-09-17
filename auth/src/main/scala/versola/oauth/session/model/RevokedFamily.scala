package versola.oauth.session.model

import versola.user.model.UserId

/** Outcome of revoking a leaked refresh-token family: what the caller needs to push the
  * revocation to the client's back channel, which is the family's own id and the user it
  * belonged to. The access tokens it issued are not enumerated -- they are named collectively
  * by `familyId`, which is what every one of them carries as its `fam` claim.
  */
case class RevokedFamily(userId: UserId, familyId: RefreshTokenFamilyId)
