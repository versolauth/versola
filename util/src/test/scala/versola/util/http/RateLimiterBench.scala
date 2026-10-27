package versola.util.http

import zio.*

import java.lang.System as JavaSystem
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, Executors}

/** A plain nanoTime microbenchmark of [[RateLimiter.check]], not part of `sbt test` (a timing is not a stable assertion):
  *
  * {{{
  * sbt "util/Test/runMain versola.util.http.RateLimiterBench"
  * }}}
  *
  * It prints the cost of one check for one and for four policies, with one thread and with several, on one hot key and on
  * many keys, for a request that passes and for one that is refused. The limits are set so that "passes" never runs out.
  * The answer to compare with is the CPU of a proxied call, about 2 ms for edge and auth together in the campaign.
  */
object RateLimiterBench:
  private val threadsContended = 8
  private val opsPerThread = 5_000_000
  private val rounds = 5

  private def policies(n: Int, refuse: Boolean): List[RateLimitPolicy] =
    val dims = List(
      List(RateLimitDimension.Client),
      List(RateLimitDimension.Subject),
      List(RateLimitDimension.Session),
      List(RateLimitDimension.Client, RateLimitDimension.Jkt),
    )
    (0 until n).toList.map: i =>
      RateLimitPolicy(
        name = s"bench$i",
        by = dims(i),
        limits = if refuse then List(RateLimitWindow(1, 3600)) else List(RateLimitWindow(1_000_000, 1), RateLimitWindow(1_000_000, 60)),
        burst = if refuse then 1 else 1_000_000,
        mode = GuardMode.Enforce,
        scope = RateLimitScope.EdgeWide,
        cost = 1,
      )

  private val keySets: Array[RateLimitKeys] = Array.tabulate(4096): i =>
    RateLimitKeys(client = s"client-$i", subject = s"subject-$i", session = s"sid-$i", jkt = s"jkt-$i")

  private val hotKey = keySets(0)

  /** Nanoseconds per check, the best of several rounds (the least disturbed). */
  private def measure(limiter: RateLimiter, threads: Int, manyKeys: Boolean): Double =
    val sink = new AtomicLong
    def run(): Double =
      val pool = Executors.newFixedThreadPool(threads)
      val ready = new CountDownLatch(threads)
      val go = new CountDownLatch(1)
      val done = new CountDownLatch(threads)
      val elapsed = new AtomicLong
      (0 until threads).foreach: t =>
        pool.execute: () =>
          ready.countDown()
          go.await()
          val started = JavaSystem.nanoTime()
          var allowed = 0L
          var i = 0
          while i < opsPerThread do
            val keys = if manyKeys then keySets((i + t * 17) & 4095) else hotKey
            // a clock that moves, so the arithmetic is the real one; the service reads Clock.nanoTime once per checkpoint
            if limiter.check(Checkpoint.Identity, keys, i.toLong * 1000L) == RateDecision.Allowed then allowed += 1
            i += 1
          elapsed.addAndGet(JavaSystem.nanoTime() - started)
          sink.addAndGet(allowed)
          done.countDown()
      ready.await()
      go.countDown()
      done.await()
      pool.shutdown()
      elapsed.get.toDouble / (threads.toLong * opsPerThread)
    run() // warm up
    (1 to rounds).map(_ => run()).min

  def main(args: Array[String]): Unit =
    Unsafe.unsafe: u =>
      given Unsafe = u
      val runtime = Runtime.default
      println(f"${"scenario"}%-52s ${"ns/check"}%10s")
      for
        refuse <- List(false, true)
        n <- List(1, 4)
        (threads, manyKeys, label) <- List(
          (1, false, "1 thread, one key"),
          (1, true, "1 thread, 4096 keys"),
          (threadsContended, false, s"$threadsContended threads, one hot key"),
          (threadsContended, true, s"$threadsContended threads, 4096 keys"),
        )
      do
        val limiter = runtime.unsafe.run(RateLimiter.make().tap(_.update(policies(n, refuse)))).getOrThrow()
        val ns = measure(limiter, threads, manyKeys)
        println(f"${s"$n polic${if n == 1 then "y" else "ies"}, ${if refuse then "refused" else "passes"}, $label"}%-52s ${ns}%10.1f")
