package versola.util.http

import zio.*

/** Runs a service's optional readiness-gated warmup: bounded by a budget and fail-open, so a
  * slow or broken warmup step can never turn into an outage.
  *
  * Used by [[VersolaApp.run]] between the application server binding and
  * [[ReadinessService.setReady]] -- the socket is open (so a warmup step can issue real loopback
  * requests) but `/readiness` still answers false, so kube-proxy has not yet added the pod to a
  * Service's endpoints. Liveness is unaffected either way: the diagnostics server (and so
  * `/liveness`) is already up before `dependencies.build` runs, deliberately (#378).
  */
object Warmup:

  /** Runs `effect` on its own fiber, logging its outcome, and returns that fiber so the caller can
    * bind its lifetime to something -- see the warning below. Never fails: a timeout or a defect
    * is logged as a warning and treated as complete, exactly like a warmup that finished within
    * budget -- proceeding to readiness is always the right thing to do, an optimization that
    * didn't pay off is not a reason to keep a pod out of rotation.
    *
    * `effect`'s own error channel is `Nothing`, so the only way it can misbehave is a timeout or
    * a defect (an unexpected exception); both are handled here identically to keep call sites
    * simple. A concrete warmup that can fail in some recoverable way is expected to sandbox its
    * own steps internally and never let the failure escape. The defect case is caught on the
    * forked fiber itself, not by the caller observing the join, because the whole point of
    * forking is that the caller may stop observing before `effect` finishes (see below) --
    * catching only at the join site would let a late defect crash that fiber silently uncaught
    * instead of being logged.
    *
    * Forking before racing the join against `budget` is what makes the budget an actual bound
    * rather than a best-effort one. A bare `timeout` interrupts the loser and then *waits* for
    * that interruption to finish, so a warmup step inside an uninterruptible region -- a blocking
    * JDBC call, a `ZIO.attemptBlocking` that ignores its interrupt flag, a tight CPU loop with no
    * yield point -- holds the expression past `budget`, and can hold it forever. `setReady` is
    * downstream, so that is a pod that never joins its Service: an optimization turned into the
    * outage this whole object exists to rule out. Racing `fiber.join` instead only interrupts the
    * *observer*; the deadline is always honoured, and the still-running step keeps going on its
    * own fiber, where it delays nothing.
    *
    * '''The returned fiber outlives this call, on purpose, and that is a liability the caller
    * must close.''' "Keeps going on its own fiber" past the deadline is fine while the app is up,
    * but nothing here stops that fiber at shutdown: left to itself, it is not registered in any
    * scope, so it can run after `dependencies` have been released and leak work across a restart
    * (an HTTP call into a closed connection pool, for instance). The caller is expected to
    * register `<fiber>.interrupt` as a finalizer on its own scope, exactly like every other
    * background fiber [[VersolaApp.run]] starts.
    */
  def run[R](effect: ZIO[R, Nothing, Unit], budget: Duration): URIO[R, Fiber.Runtime[Nothing, Unit]] =
    for
      fiber <- (ZIO.logInfo("Running warmup") *> effect)
        .catchAllCause { cause =>
          ZIO.logWarningCause("Warmup failed; proceeding to readiness anyway", cause)
        }
        .fork
      _ <- fiber.join
        .timeout(budget)
        .flatMap {
          case Some(_) => ZIO.logInfo("Warmup completed")
          case None =>
            ZIO.logWarning(s"Warmup exceeded its ${budget.render} budget; proceeding to readiness anyway")
        }
    yield fiber
