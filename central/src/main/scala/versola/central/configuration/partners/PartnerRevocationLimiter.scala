package versola.central.configuration.partners

import zio.{Clock, UIO}

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap

/** How often a partner may ask central to end grants (`POST /edge/partner/revocations`, #962 review). A revocation of every
  * grant of a client is a statement over the table that holds the first-party tokens too, and the partner's staff could loop
  * it; one of a user's is cheap. The limit is per partner and per replica (central holds no shared counter): a partner that
  * reaches several replicas gets that many, which is still bounded. */
object PartnerRevocationLimiter:

  /** Between two revocations of a whole client by one partner. */
  val ClientInterval: Duration = Duration.ofSeconds(60)

  /** Between two revocations of one user's grants by one partner. */
  val UserInterval: Duration = Duration.ofSeconds(1)

  private val last = new ConcurrentHashMap[(PartnerId, Boolean), Instant]()

  /** Forgets every count: for a test, which must not depend on what another one asked for. */
  private[central] def reset(): Unit = last.clear()

  /** `None` when the revocation may go on (and is counted), else how long to wait. */
  def acquire(partner: PartnerId, wholeClient: Boolean): UIO[Option[Duration]] =
    Clock.instant.map: now =>
      val interval = if wholeClient then ClientInterval else UserInterval
      val key = (partner, wholeClient)
      var wait: Option[Duration] = None
      last.compute(
        key,
        (_, previous) =>
          val earliest = Option(previous).map(_.plus(interval))
          if earliest.exists(_.isAfter(now)) then
            wait = earliest.map(Duration.between(now, _))
            previous
          else now,
      )
      wait
