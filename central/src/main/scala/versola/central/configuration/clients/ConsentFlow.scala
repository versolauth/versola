package versola.central.configuration.clients

import versola.central.configuration.scopes.ScopeToken
import zio.Duration
import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** Governs whether and how a client's users are shown an OAuth/OIDC consent screen
  * before an authorization code or token is issued.
  *
  * @param allowPartial whether the user may deselect optional scopes (anything other
  *                      than `openid`/`offline_access`, which are never deselectable)
  *                      on the consent screen, granting a subset of the requested scope
  * @param rememberDuration how long a granted consent is remembered and reused without
  *                          re-prompting; `None` means the grant is remembered until revoked
  * @param requiredScopes scopes the user cannot deselect when the client requests them, on top of
  *                       the always-required `openid`/`offline_access`; meaningful with `allowPartial`
  * @param offlineAccessOptional whether the user may deselect `offline_access` ("long-lived access"),
  *                              which is otherwise always required; the client then receives no
  *                              refresh token. Only meaningful with `allowPartial`
  */
case class ConsentFlow(
    allowPartial: Boolean,
    rememberDuration: Option[Duration],
    requiredScopes: Set[ScopeToken],
    offlineAccessOptional: Boolean,
) derives Schema, JsonCodec, Equal
