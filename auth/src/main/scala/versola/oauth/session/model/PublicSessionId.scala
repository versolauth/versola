package versola.oauth.session.model

import versola.util.StringNewType
import zio.json.JsonDecoder

/** Protocol-visible session identifier published as the OIDC `sid` claim.
  *
  * Deliberately unrelated to [[SessionId]] and to its storage key: `sid` travels in
  * front-channel logout URLs and therefore leaks into browser history, RP access logs
  * and `Referer` headers, so it must carry no authority and must not expose the
  * session credential or the row key derived from it.
  */
type PublicSessionId = PublicSessionId.Type

object PublicSessionId extends StringNewType.Base64Url:
  given JsonDecoder[PublicSessionId] = JsonDecoder.string.map(PublicSessionId(_))

