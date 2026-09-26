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
    /** The refresh-token family this token was issued from, absent when none stands behind it
      * (a `client_credentials` token). Revoking that family revokes this token. */
    @jsonField("fam") family: Option[RefreshTokenFamilyId] = None,
    /** Present only on a sender-constrained token, naming what its holder has to prove. */
    @jsonField("cnf") confirmation: Option[Confirmation],
) derives JsonCodec

/** What a sender-constrained token is bound to: a key under RFC 9449 §6.1, a client
  * certificate under RFC 8705 §3.1. A token carries one or the other, never both, and which
  * one decides what the holder must produce -- a DPoP proof signed with that key, or that
  * certificate on the connection.
  *
  * Both halves optional, and neither is a field a decoder may insist on: auth issues
  * `cnf: {"x5t#S256": ...}` to a mutual-TLS client, and requiring `jkt` made the whole claim
  * set undecodable for those tokens -- so every request carrying one was refused as
  * unreadable, before anything about its binding was ever looked at.
  */
case class Confirmation(
    jkt: Option[String] = None,
    @jsonField("x5t#S256") certificateThumbprint: Option[String] = None,
) derives JsonCodec
