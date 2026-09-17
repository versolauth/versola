package versola.oauth.session.model

import versola.util.StringNewType

/** Identifies a refresh-token rotation family.
  *
  * Random, and unrelated to any token in the family. It is stamped on every generation and
  * outlives all of them, so deriving it from the root token -- as it once was, being that
  * token's storage key -- kept a credential-derived value alive for as long as the grant and
  * put it anywhere the family had to be named. Nothing authenticates by presenting this: it
  * is an identity, on the same terms as [[PublicSessionId]], and carries no authority.
  */
type RefreshTokenFamilyId = RefreshTokenFamilyId.Type

object RefreshTokenFamilyId extends StringNewType.Base64Url
