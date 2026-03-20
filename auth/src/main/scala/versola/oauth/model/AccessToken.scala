package versola.oauth.model

import versola.util.{Base64, ByteArrayNewType}
import zio.json.JsonCodec

type AccessToken = AccessToken.Type

object AccessToken extends ByteArrayNewType:
  given JsonCodec[AccessToken] =
    JsonCodec.string.transformOrFail(s => fromBase64Url(s), Base64.urlEncode)

  extension (at: AccessToken)
    def encoded: String = Base64.urlEncode(at)