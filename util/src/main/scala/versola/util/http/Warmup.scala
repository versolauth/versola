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

  /** Runs `effect`, logging its outcome. Never fails: a timeout or a defect is logged as a
    * warning and treated as complete, exactly like a warmup that finished within budget --
    * proceeding to readiness is always the right thing to do, an optimization that didn't pay
    * off is not a reason to keep a pod out of rotation.
    *
    * `effect`'s own error channel is `Nothing`, so the only way it can misbehave is a timeout or
    * a defect (an unexpected exception); both are handled here identically to keep call sites
    * simple. A concrete warmup that can fail in some recoverable way is expected to sandbox its
    * own steps internally and never let the failure escape.
    *
    * `disconnect` before `timeout` is what makes the budget an actual bound rather than a
    * best-effort one. A bare `timeout` interrupts the loser and then *waits* for that
    * interruption to finish, so a warmup step inside an uninterruptible region -- a blocking JDBC
    * call, a `ZIO.attemptBlocking` that ignores its interrupt flag, a tight CPU loop with no
    * yield point -- holds the expression past `budget`, and can hold it forever. `setReady` is
    * downstream, so that is a pod that never joins its Service: an optimization turned into the
    * outage this whole object exists to rule out. Disconnected, the timeout returns at the
    * deadline and the orphaned step finishes interrupting on its own fiber, where it delays
    * nothing.
    */
  def run[R](effect: ZIO[R, Nothing, Unit], budget: Duration): URIO[R, Unit] =
    (ZIO.logInfo("Running warmup") *> effect).disconnect
      .timeout(budget)
      .flatMap {
        case Some(_) => ZIO.logInfo("Warmup completed")
        case None =>
          ZIO.logWarning(s"Warmup exceeded its ${budget.render} budget; proceeding to readiness anyway")
      }
      .catchAllCause { cause =>
        ZIO.logWarningCause("Warmup failed; proceeding to readiness anyway", cause)
      }
