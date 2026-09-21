package versola.oauth.authorize.model

import zio.json.*
import zio.schema.*

/** RFC 6749 §5.2-shaped body for the `/authorize` endpoint's direct (non-redirect) error
  * responses -- the ones answered before a `redirect_uri` can be trusted, so there is nowhere
  * to put `error` but the body itself.
  */
private[authorize] case class AuthorizeErrorResponse(
    error: String,
    @jsonField("error_description") errorDescription: String,
) derives Schema, JsonCodec
