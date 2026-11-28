package versola.oauth.session.model

import versola.util.{Base64, RoutingKey, StringNewType}
import zio.json.JsonDecoder

/** Protocol-visible session identifier published as the OIDC `sid` claim.
  *
  * Deliberately unrelated to [[SessionId]] and to its storage key: `sid` travels in
  * front-channel logout URLs and therefore leaks into browser history, RP access logs
  * and `Referer` headers, so it must carry no authority and must not expose the
  * session credential or the row key derived from it.
  *
  * Issued under this deployment's cell id when it has one (`v1.<cell>.<base64url>`, versolauth/versola#606), so that a
  * router can send a front-channel logout or a `sid` hint to the cell that holds the session. Nothing reads the id back
  * apart from by equality with what was stored, so the prefixed and the unprefixed form need no other handling: a
  * session issued before the cell id was set keeps the id it has.
  */
type PublicSessionId = PublicSessionId.Type

object PublicSessionId extends StringNewType.Base64Url:
  def issue(cell: Option[RoutingKey.CellId], random: Array[Byte]): PublicSessionId =
    cell.fold(fromBytes(random))(cell => apply(RoutingKey.encode(cell, Base64.urlEncode(random))))

  given JsonDecoder[PublicSessionId] = JsonDecoder.string.map(PublicSessionId(_))
