package versola.loadgen.model

/** Which protocol surface a session was established through -- a mobile bearer token
  * (`AuthClient`) or an edge cookie session (`EdgeClient`). See §8.1-8.4.
  */
enum SessionKind:
  case MobileToken, WebCookie

/** One row of `vu_sessions` (§6, migration L0002). `generation` is bumped before every refresh
  * exchange, not after -- see §7.4's refresh discipline -- so a crash between the bump and the
  * exchange retires the session on restart instead of replaying it into reuse detection.
  */
case class DeviceSession(
    id: Long,
    userId: Long,
    kind: SessionKind,
    clientId: String,
    generation: Int,
    shard: Int,
)
