package versola.loadgen.seed

import zio.Duration

/** The campaign-wide values a warm session's `refresh_tokens` row needs and that this package has
  * no business re-deriving: [[versola.loadgen.provision.CampaignBlueprint]] already computed them
  * once, from the same `LoadgenConfig` the seeder reads (see [[Seeder]]).
  *
  * @param audience
  *   the resolved resource URIs (`core`/`pay`/`notify`) every client is audience-listed on --
  *   `RefreshTokenRecord.audience`, not `ResourceSpec.audience` (client ids); a mismatch here is
  *   invisible until a resumed session's first API call 403s on the wrong `aud`.
  * @param scope
  *   [[versola.loadgen.provision.CampaignBlueprint.scopes]], every client's fixed request.
  * @param names
  *   the client ids the warm sessions are issued to
  * @param refreshTokenTtl
  *   [[versola.loadgen.config.SessionConfig.refreshTokenTtl]], so a warm token's `expires_at`
  *   agrees with what the session model already assumes a live refresh token's lifetime is.
  */
case class WarmSessionConfig(
    audience: List[String],
    scope: List[String],
    refreshTokenTtl: Duration,
    /** The campaign's client ids, namespaced when `provision.namespace` is: a warm token is bound
      * to the client it was issued to, so one seeded for `mobile-otp` is refused (`invalid_grant`)
      * when the driver refreshes as `fapi-mobile-otp`. */
    names: versola.loadgen.provision.CampaignBlueprint.Names = versola.loadgen.provision.CampaignBlueprint.Names(None),
)

/** The credentials of one warm mobile session (§10 step 6): a refresh token auth will accept
  * from a `grant_type=refresh_token` exchange the user never actually performed.
  *
  * Mirrors [[PasskeyMaterial]] in spirit -- per-user cryptographic material generated at seed
  * time rather than derived from the id, because a MAC key is exactly the kind of thing that
  * must not be reconstructable from a `vu_users.id` an attacker also has -- but the two MACs
  * here come from auth's own [[versola.util.SecurityService.mac]], not from anything the seeder
  * computes itself: see [[BulkTokenMinter]].
  *
  * @param rawToken
  *   the 32 bytes the driver will present verbatim as `refresh_token` on its first resume
  *   (`AuthPropertyGenerator.nextRefreshToken`'s own shape) -- stored, base64url-encoded, in
  *   `vu_sessions.refresh_token` so the emulator's own store can hand it back.
  * @param tokenMac
  *   `MAC(rawToken, refreshTokensSecret)`, exactly what auth recomputes on the incoming request
  *   and looks `refresh_tokens.id` up by. Getting this wrong is silent until the first refresh:
  *   the row exists, the token looks plausible, and auth answers `invalid_grant` anyway.
  * @param sessionMac
  *   `MAC(rawSessionId, sessionsSecret)` for `refresh_tokens.session_id`. Never read back by a
  *   refresh exchange (`OAuthTokenService.refreshAccessToken` has no `sso_sessions` lookup), so
  *   the raw session id behind it is discarded once this is computed -- nothing downstream ever
  *   needs it again.
  * @param familyId
  *   16 random bytes for `refresh_tokens.family_id`, in [[AuthPropertyGenerator.nextRefreshTokenFamilyId]]'s
  *   own shape. Names the rotation chain a replay would revoke; a warm session that never rotates
  *   before it is first used still needs one, because auth writes it into the row it stores.
  * @param publicSessionId
  *   16 random bytes for `refresh_tokens.public_session_id`, in
  *   [[AuthPropertyGenerator.nextPublicSessionId]]'s own shape -- observability-only on the
  *   refresh path (`Observability.setSessionId`), never looked up.
  * @param dpopJkt
  *   the RFC 7638 thumbprint of the DPoP key [[versola.loadgen.protocol.DpopKeyPool.keyFor]]
  *   assigns this user, written as `refresh_tokens.cnf = {"jkt": ...}`. `None` on a bearer
  *   campaign. Without it a seeded session's first refresh under DPoP is refused: auth holds a
  *   refresh token to the key it was issued to, and a row with no binding was issued to none --
  *   the first proof's key would be accepted and then bound, but a tenant that requires a
  *   sender-constrained refresh refuses the unbound row outright.
  */
case class RefreshTokenMaterial(
    rawToken: Array[Byte],
    tokenMac: Array[Byte],
    sessionMac: Array[Byte],
    familyId: Array[Byte],
    publicSessionId: Array[Byte],
    dpopJkt: Option[String],
)
