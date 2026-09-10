package versola.loadgen.model

import versola.loadgen.protocol.{EdgeSession, RefreshToken}

import java.time.Instant

/** Which protocol surface a session was established through -- a mobile bearer token
  * (`AuthClient`) or an edge cookie session (`EdgeClient`). See §8.1-8.4.
  */
enum SessionKind:
  case MobileToken, WebCookie

/** One row of `vu_sessions` (§6, migration V0002). `generation` is bumped before every refresh
  * exchange, not after -- see §7.4's refresh discipline -- so a crash between the bump and the
  * exchange retires the session on restart instead of replaying it into reuse detection.
  *
  * Field order matches V0002's column order for the same reason [[VirtualUser]]'s does, and this
  * is likewise the persisted shape rather than a projection of it: a session without its
  * credentials cannot be resumed, which is the only reason a driver loads one at startup.
  *
  * `refreshToken` and `edgeCookie` are the protocol newtypes rather than `String` because the
  * two are mutually exclusive per `kind` and interchanging them is a bug no query would reject
  * -- the session would simply never authenticate again and read as a phantom SUT failure
  * (§8.4).
  *
  * `acr` is the assurance level the session currently holds, written on the critical path by
  * every occasion that changes it (a refresh exchange or a completed step-up), not on the
  * deferred one. See `DeviceSessionRepository.storeStepUp`.
  */
case class DeviceSession(
    id: Long,
    userId: Long,
    kind: SessionKind,
    clientId: String,
    refreshToken: Option[RefreshToken],
    edgeCookie: Option[EdgeSession],
    accessExpiresAt: Option[Instant],
    refreshExpiresAt: Option[Instant],
    acr: Option[String],
    authTime: Option[Instant],
    generation: Int,
    shard: Int,
)
