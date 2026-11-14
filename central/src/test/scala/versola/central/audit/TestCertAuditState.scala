package versola.central.audit

import versola.central.configuration.clients.ClientId
import versola.central.configuration.clients.certificates.RenewalFailures
import zio.{Duration, Ref, Unsafe}

import java.time.Instant

/** The memory a certificate service keeps for its audit events, for a spec whose environment is built outside an effect. */
object TestCertAuditState:

  def renewalFailures(): RenewalFailures =
    RenewalFailures(Unsafe.unsafe(Ref.unsafe.make(Map.empty[ClientId, String])(using _)))

  def throttle(interval: Duration): DenialThrottle =
    DenialThrottle(interval, Unsafe.unsafe(Ref.unsafe.make(Map.empty[String, Instant])(using _)))

  def throttle(): DenialThrottle = throttle(DenialThrottle.Interval)
