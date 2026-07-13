package versola.auth.model

import versola.util.{Base64, ByteArrayNewType}
import zio.json.JsonCodec
import zio.schema.Schema

type CredentialId = CredentialId.Type

object CredentialId extends ByteArrayNewType:
  given JsonCodec[CredentialId] =
    JsonCodec.string.transformOrFail(fromBase64Url, Base64.urlEncode)

  given Schema[CredentialId] =
    Schema.primitive[String].transformOrFail(fromBase64Url, bytes => Right(Base64.urlEncode(bytes)))
