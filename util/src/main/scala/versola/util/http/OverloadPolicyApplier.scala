package versola.util.http

import zio.*

/** Keeps an [[InboundAdmission]] and an [[OutboundGuard]] at the policy a source currently holds.
  *
  * The policy is a patch over the settings the process started with (`baseInbound`, `baseOutbound`), so removing a field, or
  * the whole policy, returns that part to the deployment's values. A policy that fails [[OverloadPolicy.validate]] is not
  * applied and the previous settings stay: central validates before it stores, so this is for a central that is older or
  * newer than this edge, not for the usual case.
  */
object OverloadPolicyApplier:
  /** Applies `policy` over the base settings, if it is valid. `Left` carries what was wrong with it. */
  def apply(
      policy: Option[OverloadPolicy],
      admission: InboundAdmission,
      guard: OutboundGuard,
      baseInbound: InboundAdmissionSettings,
      baseOutbound: OutboundGuardSettings,
  ): UIO[Either[String, Unit]] =
    policy.getOrElse(OverloadPolicy.empty).validate match
      case Left(why) => ZIO.succeed(Left(why))
      case Right(valid) =>
        admission.update(valid.applyInbound(baseInbound)) *> guard.update(valid.applyOutbound(baseOutbound)) *> ZIO.succeed(Right(()))

  /** Polls `source` once a second and applies what it yields when it changed, for the life of the service. A change is logged
    * once; nothing is logged while the policy stays the same.
    */
  def run(
      source: UIO[Option[OverloadPolicy]],
      admission: InboundAdmission,
      guard: OutboundGuard,
      baseInbound: InboundAdmissionSettings,
      baseOutbound: OutboundGuardSettings,
  ): UIO[Nothing] =
    Ref.make[Option[Option[OverloadPolicy]]](None).flatMap: applied =>
      source
        .flatMap: policy =>
          applied.get.flatMap: previous =>
            if previous.contains(policy) then ZIO.unit
            else
              OverloadPolicyApplier(policy, admission, guard, baseInbound, baseOutbound).flatMap:
                case Right(_) =>
                  applied.set(Some(policy)) *>
                    ZIO.logInfo(s"overload policy applied: ${policy.fold("none (process settings)")(_.toString)}")
                case Left(why) =>
                  // remembered, so a policy that stays invalid is reported once and not every second
                  applied.set(Some(policy)) *> ZIO.logWarning(s"overload policy not applied: $why")
        .repeat(Schedule.fixed(1.second))
        .forever
