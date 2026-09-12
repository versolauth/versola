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
    @jsonField("tenant_id") tenantId: TenantId,
    roles: List[RoleId],
    acr: Option[String],
    @jsonField("auth_time") authTime: Option[Long],
    @jsonField("sid") sid: Option[SessionId] = None,
    /** RFC 9449 §6.1: present only on a sender-constrained token. Its `jkt` is the thumbprint
      * of the key the accompanying DPoP proof must be signed with, and its mere presence is
      * what makes the `Bearer` scheme inadmissible for this token (§7.2). */
    @jsonField("cnf") confirmation: Option[Confirmation] = None,
) derives JsonCodec

case class Confirmation(jkt: String) derives JsonCodec
