package versola.antifraud

import zio.Duration

import java.util.concurrent.atomic.{AtomicInteger, AtomicLongArray}

/** The last `capacity` times a real code took to reach the provider, so that a fake send can wait as long as a real
  * one does. Lock-free, fixed memory: a slot is overwritten by whichever send comes next. */
final class SendLatency(capacity: Int = 256):
  private val nanos = new AtomicLongArray(capacity)
  private val written = new AtomicInteger(0)

  def add(took: Duration): Unit =
    nanos.set(Math.floorMod(written.getAndIncrement(), capacity), Math.max(1L, took.toNanos))

  /** The duration of one of the recent real sends, picked by `pick`; zero before any was measured. */
  def sample(pick: Int): Duration =
    val n = Math.min(written.get, capacity)
    if n == 0 then Duration.Zero
    else
      val v = nanos.get(Math.floorMod(pick, n))
      if v == 0L then Duration.Zero else Duration.fromNanos(v)
