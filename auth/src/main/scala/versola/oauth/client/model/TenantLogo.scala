package versola.oauth.client.model

import zio.json.JsonCodec

/** A tenant's logo as the login forms get it: the file for the light colour scheme (dark artwork) and the one for
  * the dark scheme (light artwork). Always a pair: a tenant with one file only has no logo.
  */
case class TenantLogo(light: String, dark: String) derives JsonCodec
