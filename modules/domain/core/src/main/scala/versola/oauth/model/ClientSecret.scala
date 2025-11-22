package versola.oauth.model

import versola.util.StringNewType

/** Base64URL-encoded client secret returned to client on registration */
type ClientSecret = ClientSecret.Type

object ClientSecret extends StringNewType.Base64Url