package versola.edge.model

import versola.util.StringNewType
import zio.json.{JsonDecoder, JsonEncoder}

/** The `fam` claim: the refresh-token rotation family this access token was issued from.
  *
  * Carried so that revoking a leaked family names the family itself rather than the tokens
  * it happens to have issued, which auth would otherwise have to enumerate and keep a record
  * of. Absent on a token no family stands behind -- a `client_credentials` one.
  */
type RefreshTokenFamilyId = RefreshTokenFamilyId.Type

object RefreshTokenFamilyId extends StringNewType:
  given JsonDecoder[RefreshTokenFamilyId] = JsonDecoder.string.map(RefreshTokenFamilyId(_))
  given JsonEncoder[RefreshTokenFamilyId] = JsonEncoder.string.contramap(identity[String])
