package versola.central.configuration.clients

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*
import zio.Duration

/** Governs whether and how a client's users are shown an OAuth/OIDC consent screen
  * before an authorization code or token is issued.
  *
  * @param allowPartial whether the user may deselect optional scopes (anything other
  *                      than `openid`/`offline_access`, which are never deselectable)
  *                      on the consent screen, granting a subset of the requested scope
  * @param rememberDuration how long a granted consent is remembered and reused without
  *                          re-prompting; `None` means the grant is remembered until revoked
  */
case class ConsentFlow(
    allowPartial: Boolean,
    rememberDuration: Option[Duration],
) derives Schema, JsonCodec, Equal
