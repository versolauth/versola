package versola.oauth.session.model

import versola.oauth.client.model.{Acr, AuthMethodRef}
import versola.util.MAC

import java.time.Instant

/** What to do with the prior session (and its refresh tokens) when creating a new session. */
enum PriorSession:
  def id: MAC.Of[SessionId]

  /** Expire the prior session and all its refresh tokens. Use for true re-authentication
   *  (prompt=login, account switch) where the old credential must be fully invalidated. */
  case Invalidate(id: MAC.Of[SessionId])
  /** Expire the prior session but re-parent its refresh tokens to the new session,
   *  updating their auth context (amr, authTime, acr) to reflect the step-up.
   *  Use when the client holds refresh tokens on-device (offline_access scope). */
  case MigrateTokens(
    id: MAC.Of[SessionId],
    amr: Set[AuthMethodRef],
    authTime: Instant,
    acr: Option[Acr],
  )
