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
  * @param requiredScopes scopes the user cannot deselect when they are requested, in addition to
  *                       `openid`/`offline_access`; only meaningful with `allowPartial`
  */
case class ConsentFlow(
    allowPartial: Boolean,
    rememberDuration: Option[Duration],
    requiredScopes: Set[ScopeToken] = Set.empty,
) derives Schema, JsonCodec, Equal
