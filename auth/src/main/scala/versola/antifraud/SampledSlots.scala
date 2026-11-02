package versola.antifraud

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicInteger, AtomicReferenceArray}

/** A fixed number of slots that hold the members of a table, so that a few of them can be picked at random: what a
  * `ConcurrentHashMap` cannot do, and what evicting by "the least used of a random sample" needs (the power of choices:
  * of eight random members the least counted goes, so a member that is counted a lot is evicted with a chance that falls
  * off with the eighth power of its share of the table, however fast keys are made up).
  *
  * A member takes a slot when it is admitted and gives it back when it leaves; a slot is never searched for. The
  * sample is taken with `ThreadLocalRandom`: the choice is a defence against a flood of keys and has no secret to
  * protect (an attacker sees only which of its own keys were evicted, and cannot steer the others' generator).
  */
private[antifraud] final class SampledSlots[A <: AnyRef](capacity: Int):
  private val slots = AtomicReferenceArray[A](capacity)
  // Slots handed out so far, never fewer than the highest in use + 1; freed ones are reused before a new one is cut.
  private val used = AtomicInteger(0)
  private val freed = ConcurrentLinkedQueue[Integer]()

  /** The slot `member` now holds, or -1 when all `capacity` slots are taken (the table is over its size by a race). */
  def claim(member: A): Int =
    val reused = freed.poll()
    val slot =
      if reused != null then reused.intValue
      else
        val next = used.getAndUpdate(n => if n < capacity then n + 1 else n)
        if next < capacity then next else -1
    if slot >= 0 then slots.set(slot, member)
    slot

  /** Gives the slot back; only if `member` still holds it, so that a double release does not free another's slot. */
  def release(slot: Int, member: A): Unit =
    if slot >= 0 && slots.compareAndSet(slot, member, null.asInstanceOf[A]) then freed.add(slot)

  /** Up to `count` distinct members picked at random, fewer when the table is that small or the probes miss. */
  def sample(count: Int): List[A] =
    val high = used.get
    if high == 0 then Nil
    else
      val random = ThreadLocalRandom.current()
      var picked = List.empty[A]
      var probes = 0
      // A few probes per member wanted: a half-empty table still yields them, an empty one yields none.
      while picked.length < count && probes < count * 4 do
        probes += 1
        val member = slots.get(random.nextInt(high))
        if member != null && !picked.exists(_.eq(member)) then picked = member :: picked
      picked
