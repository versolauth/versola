package versola.oauth.client.model

import zio.schema.{Schema, derived}

/** External OAuth client for managing provider credentials (Google, GitHub, etc.) */
case class ExternalOAuthClient(
    id: Long,
    provider: OauthProviderName,
    clientId: ClientId,
    clientSecret: ClientSecret,
    oldClientSecret: Option[ClientSecret] = None,
) derives Schema