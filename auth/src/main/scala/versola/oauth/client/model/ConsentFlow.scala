package versola.oauth.client.model

import zio.Duration
import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** Governs whether and how a client's users are shown an OAuth/OIDC consent screen
  * before an authorization code is issued. Absent for first-party clients, which never prompt.
  *
  * @param allowPartial whether the user may deselect optional scopes (anything other than
  *                      `openid`/`offline_access`, which are never deselectable) and grant a
  *                      subset of the requested scope
  * @param rememberDuration how long a granted consent is reused without re-prompting; `None`
  *                          means the grant is remembered until revoked
  */
case class ConsentFlow(
    allowPartial: Boolean,
    rememberDuration: Option[Duration],
) derives Schema, JsonCodec, Equal
