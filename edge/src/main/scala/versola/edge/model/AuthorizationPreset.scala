package versola.edge.model

import versola.util.RedirectUri
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class AuthorizationPreset(
    id: PresetId,
    clientId: ClientId,
    description: String,
    redirectUri: RedirectUri,
    postLoginRedirectUri: RedirectUri,
    scope: Set[String],
    responseType: String,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
    cookieDomain: Option[String],
    cookiePath: Option[String],
) derives Schema, JsonCodec
