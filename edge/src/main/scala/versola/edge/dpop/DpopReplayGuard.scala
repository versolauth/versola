package versola.edge.dpop

import org.apache.commons.codec.digest.Blake3
import versola.edge.EdgeConfig
import zio.{Clock, Duration, Schedule, Scope, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** RFC 9449 §4.3 step 12 / §11.1: rejects a proof whose `jti` has already been seen inside the
  * `iat` window.
  *
  * Deliberately in memory, not in Postgres. The edge proxies every API call in the system,
  * and today it does so without touching the database at all -- the JWKS is cached, the
  * revocation list is in memory, permissions are cached. Recording a `jti` per request would
  * put one write transaction on the critical path of every business call, which at the volumes
  * this fleet is sized for is several times the total write load of the rest of the system,
  * and would make the edge's tail latency a function of the database's. §11.1 anticipates
  * exactly this trade-off: a strict single-use check "may not always be feasible in practice,
  * e.g., when multiple servers behind a single endpoint have no shared state."
  *
  * What the per-pod cache gives up is a proof replayed to a *different* replica within the
  * window. Three things bound that: `ath` ties the proof to one access token, `htm`/`htu` tie
  * it to one method and URI, so the only thing a successful replay achieves is re-sending a
  * request the holder of the key already sent; and a required nonce (§9, off by default) caps
  * the window at the nonce TTL. Routing by `jkt` at the ingress closes it outright, since one
  * key then only ever reaches one replica.
  *
  * What it gains, beyond the write, is that one clock governs both admission and eviction.
  * A shared store is evicted by whichever replica's timer fires first, so a replica running
  * fast can drop a record while a slower one would still accept the proof it belonged to --
  * which is why auth's ring has to hold each slot open across an assumed skew bound. Here
  * the pod that accepted a proof is the pod that forgets it.
  */
trait DpopReplayGuard:
  /** True when this `(jkt, jti)` had not been seen; false when it is a replay. */
  def recordIfAbsent(jkt: String, jti: String, iat: Instant): UIO[Boolean]

object DpopReplayGuard:

  /** Ring geometry. A proof is filed under the slot its own `iat` falls in, so a slot can be
    * discarded wholesale once everything it could hold has left the `iat` window -- the same
    * shape as auth's `dpop_proofs` partition ring, for the same reason: expiry becomes
    * dropping a bucket rather than scanning for individual entries.
    */
  val SlotCount = 8
  val SlotWidth: Duration = Duration.fromSeconds(30)

  /** The widest `iat` leeway this geometry can hold without a slot being reused while proofs
    * routed to it are still acceptable. Both directions of the leeway have to fit, plus the
    * arbitrary slot phase either end of that span sits at, inside one lap:
    * `2 * leeway + 2 * SlotWidth <= SlotCount * SlotWidth`.
    *
    * Auth's `dpop_proofs` ring budgets a further `4 * MaxClockSkew` here, because the
    * instance accepting a proof and the instance evicting its slot are different instances
    * on different clocks. This ring needs no such margin -- see below -- which is why 8
    * slots carry the same 90s leeway auth needs 12 for.
    */
  val MaxIatLeeway: Duration = Duration.fromSeconds(
    (SlotCount * SlotWidth.toSeconds - 2 * SlotWidth.toSeconds) / 2,
  )

  def live: ZLayer[EdgeConfig & Scope, IllegalArgumentException, DpopReplayGuard] =
    ZLayer.fromZIO:
      for
        config <- ZIO.serviceWith[EdgeConfig](_.dpop)
        iatLeeway = config.map(_.iatLeeway).getOrElse(Duration.fromSeconds(60))
        _ <- ZIO.fail(
          IllegalArgumentException(
            s"dpop.iat-leeway of $iatLeeway exceeds $MaxIatLeeway, the longest window the " +
              s"$SlotCount-slot replay guard can hold without reusing a slot that still " +
              "admits proofs",
          ),
          // Compared whole rather than in seconds: truncating would admit a leeway of 90.5s
          // under a 90s bound, which is the one thing this guard's geometry depends on.
        ).when(iatLeeway.compareTo(MaxIatLeeway) > 0)
        guard = Impl()
        _ <- Clock.instant
          .flatMap(now => guard.evictStaleSlot(now, iatLeeway))
          .repeat(Schedule.spaced(SlotWidth))
          .forkScoped
      yield guard

  /** Which slot a proof created at `iat` belongs to. Laps the ring, so two proofs a full lap
    * apart share a slot -- by then the earlier one's slot has been cleared.
    */
  private def slotOf(iat: Instant): Int =
    Math.floorMod(Math.floorDiv(iat.getEpochSecond, SlotWidth.toSeconds), SlotCount.toLong).toInt

  /** §11.1 recommends storing only a hash of the `jti`, which also bounds what an oversized
    * `jti` can cost. The `jkt` is folded in so the pair, not the `jti` alone, has to be
    * unique -- two clients are free to pick the same `jti`.
    */
  private def digestOf(jkt: String, jti: String): List[Byte] =
    val digest = Array.ofDim[Byte](16)
    Blake3.initHash()
      .update(jkt.getBytes(StandardCharsets.UTF_8))
      .update(Array[Byte](0))
      .update(jti.getBytes(StandardCharsets.UTF_8))
      .doFinalize(digest)
    digest.toList

  class Impl extends DpopReplayGuard:
    private val slots: Array[java.util.Set[List[Byte]]] =
      Array.fill(SlotCount)(ConcurrentHashMap.newKeySet[List[Byte]]())

    override def recordIfAbsent(jkt: String, jti: String, iat: Instant): UIO[Boolean] =
      ZIO.succeed:
        // No expiry is compared here. A slot is only cleared once everything it holds has left
        // the `iat` window, so a proof whose record is gone has already been rejected by the
        // `iat` check in `Dpop.verify` before reaching this point.
        slots(slotOf(iat)).add(digestOf(jkt, jti))

    /** Clears the slot that has just fallen out of reach of any acceptable `iat`.
      *
      * Only running early is dangerous: dropping the record of a proof still inside the window
      * would let that proof be replayed. Running late costs memory and nothing else, since a
      * record left behind can only ever reject, never admit.
      */
    def evictStaleSlot(now: Instant, iatLeeway: Duration): UIO[Unit] =
      ZIO.succeed:
        slots(slotOf(now.minus(iatLeeway).minusSeconds(SlotWidth.toSeconds))).clear()

    /** Total records held, for the metric that sizes the window. */
    def size: UIO[Int] = ZIO.succeed(slots.map(_.size).sum)
