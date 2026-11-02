package versola.util.http

import zio.*

/** Keeps [[Observability.accessLog]] at the policy a source currently holds, the access-log counterpart of
  * [[OverloadPolicyApplier]].
  *
  * The policy is a patch over the settings the process started with (`base`), so removing a field, or the whole policy,
  * returns that part to the deployment's values. A policy that fails [[AccessLogPolicy.validate]] is not applied and the
  * previous settings stay: central validates before it stores, so this is for a central that is older or newer than this
  * edge, not for the usual case.
  */
object AccessLogPolicyApplier:
  /** Applies `policy` over `base` through `update` (the process's [[Observability.accessLog]] unless a test says otherwise), if it is valid. `Left` carries what was wrong with it. */
  def apply(
      policy: Option[AccessLogPolicy],
      base: AccessLogSettings,
      update: AccessLogSettings => UIO[Unit] = Observability.updateAccessLog,
  ): UIO[Either[String, Unit]] =
    policy.getOrElse(AccessLogPolicy.empty).validate match
      case Left(why) => ZIO.succeed(Left(why))
      case Right(valid) => update(valid.applyTo(base)).as(Right(()))

  /** Polls `source` once a second and applies what it yields when it changed, for the life of the service. A change is logged
    * once; nothing is logged while the policy stays the same.
    */
  def run(
      source: UIO[Option[AccessLogPolicy]],
      base: AccessLogSettings,
      update: AccessLogSettings => UIO[Unit] = Observability.updateAccessLog,
  ): UIO[Nothing] =
    Ref.make[Option[Option[AccessLogPolicy]]](None).flatMap: applied =>
      source
        .flatMap: policy =>
          applied.get.flatMap: previous =>
            if previous.contains(policy) then ZIO.unit
            else
              AccessLogPolicyApplier(policy, base, update).flatMap:
                case Right(_) =>
                  applied.set(Some(policy)) *>
                    ZIO.logInfo(s"access log policy applied: ${policy.fold("none (process settings)")(_.toString)}")
                case Left(why) =>
                  // remembered, so a policy that stays invalid is reported once and not every second
                  applied.set(Some(policy)) *> ZIO.logWarning(s"access log policy not applied: $why")
        .repeat(Schedule.fixed(1.second))
        .forever
