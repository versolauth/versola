package versola.oauth.model

import zio.schema.Schema
import zio.schema.derived

/** External OAuth client for managing provider credentials (Google, GitHub, etc.) */
case class ExternalOAuthClient(
    id: Long,
    provider: OauthProviderName,
    clientId: ClientId,
    clientSecret: ClientSecret,
    oldClientSecret: Option[ClientSecret] = None,
) derives Schema