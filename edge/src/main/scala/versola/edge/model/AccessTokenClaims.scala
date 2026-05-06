package versola.edge.model

import zio.json.{JsonCodec, jsonField}

case class AccessTokenClaims(
    @jsonField("jti") jti: AccessTokenId,
    @jsonField("sub") subject: String,
    @jsonField("client_id") clientId: ClientId,
    @jsonField("iss") issuer: String,
    @jsonField("aud") audience: List[String],
    @jsonField("exp") expiresAt: Long,
    @jsonField("iat") issuedAt: Long,
    roles: List[RoleId] = Nil,
) derives JsonCodec
