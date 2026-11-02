package versola.antifraud

import zio.Duration

import java.util.concurrent.atomic.{AtomicInteger, AtomicLongArray}

/** The last `capacity` times a real code took to reach the provider, so that a fake send can wait as long as a real
  * one does: a pumper who times the answers would otherwise tell the fast ones, which are never delivered, from the
  * rest. Lock-free, fixed memory: a slot is overwritten by whichever send comes next. */
final class SendLatency(capacity: Int = SendLatency.Capacity):
  private val nanos = new AtomicLongArray(capacity)
  private val written = new AtomicInteger(0)

  def add(took: Duration): Unit =
    nanos.set(Math.floorMod(written.getAndIncrement(), capacity), Math.max(1L, took.toNanos))

  /** The duration of one of the recent real sends, picked by `pick`; `None` before any was measured. */
  def sample(pick: Int): Option[Duration] =
    val n = Math.min(written.get, capacity)
    if n == 0 then None
    else
      val v = nanos.get(Math.floorMod(pick, n))
      // A slot that was reserved by `add` but not written yet reads 0: the next sample will do.
      if v == 0L then None else Some(Duration.fromNanos(v))

object SendLatency:
  val Capacity = 256
