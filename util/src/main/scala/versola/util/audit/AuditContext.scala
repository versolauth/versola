package versola.util.audit

import zio.{FiberRef, UIO, Unsafe, ZIO}

import java.util.UUID

/** What the request being handled is about, for the audit events recorded in it, filled in from wherever it is known: a
  * controller knows the target, a service what it changed, an internal endpoint who the admin behind the call is. Reset per
  * request by `Observability.middleware`, which also notes the request's `User-Agent`, so nothing leaks into the next request. */
object AuditContext:
  case class State(
      tenantId: Option[String] = None,
      actor: Option[(ActorType, Option[String])] = None,
      userId: Option[UUID] = None,
      target: Option[(String, String)] = None,
      changed: Set[String] = Set.empty,
      userAgent: Option[String] = None,
  )

  object State:
    val empty: State = State()

  val ref: FiberRef[State] = Unsafe.unsafe { case given Unsafe =>
    FiberRef.unsafe.make(State.empty)
  }

  val current: UIO[State] = ref.get

  /** Runs `zio` with a context of its own, starting from `initial`: what the request's middleware does. */
  def reset[R, E, A](initial: State)(zio: ZIO[R, E, A]): ZIO[R, E, A] = ref.locally(initial)(zio)

  extension [R, E, A](zio: ZIO[R, E, A])
    /** [[reset]] as a suffix, so that it wraps an expression without re-indenting it. */
    def withAuditContext(initial: State): ZIO[R, E, A] = reset(initial)(zio)

  def setTenant(tenantId: String): UIO[Unit] = ref.update(_.copy(tenantId = Some(tenantId)))

  def setTarget(targetType: String, targetId: String): UIO[Unit] = ref.update(_.copy(target = Some((targetType, targetId))))

  /** The names of the fields an update changed; never their values, and never a secret's name with a hint of its value. */
  def setChanged(names: Set[String]): UIO[Unit] = ref.update(_.copy(changed = names))

  def setActor(actorType: ActorType, actorId: Option[String]): UIO[Unit] = ref.update(_.copy(actor = Some((actorType, actorId))))

  def setUser(userId: UUID): UIO[Unit] = ref.update(_.copy(userId = Some(userId)))

  /** The user agent an event stores: the raw `User-Agent` header as the middleware noted it, or the short parsed form where the
    * service has one (for instance `browser version / os`). */
  def setUserAgent(userAgent: String): UIO[Unit] = ref.update(_.copy(userAgent = Some(userAgent)))
