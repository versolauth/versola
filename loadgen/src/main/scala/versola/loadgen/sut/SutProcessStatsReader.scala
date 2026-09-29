package versola.loadgen.sut

/** Parses the Prometheus text exposition a SUT service serves on its diagnostics port into one
  * [[SutProcessStats]] reading.
  *
  * A deliberately partial parser, not a general one. It answers a fixed list of names, sums the
  * series that are per-collector or per-area, and ignores everything else -- the endpoint carries
  * a few hundred series and this needs eight numbers. A general parser would be more code and
  * more to be wrong about.
  *
  * Why parse at all rather than ask a Prometheus that has already done it: the same trade
  * [[SutStatsCapture]] documents for `pg_stat_*`. A campaign boundary is an instant only this
  * coordinator knows, and a range query approximating it against a scrape interval is a
  * different measurement with the same name. The scrape is also one HTTP GET against an endpoint
  * that is already there, against a Prometheus that must be deployed, retained long enough, and
  * reachable from wherever the report is read.
  */
object SutProcessStatsReader:

  /** Names read from the exposition. `process_*` come from the JVM's own process metrics and
    * `jvm_*` from the memory/GC/thread collectors -- all of them published by
    * `VersolaApp.jvmRuntimeMetrics`, which is what a SUT must be running for this to return
    * anything.
    */
  private val CpuSeconds = "process_cpu_seconds_total"
  private val StartTimeSeconds = "process_start_time_seconds"
  private val ResidentMemoryBytes = "process_resident_memory_bytes"
  private val OpenFds = "process_open_fds"
  private val MemoryUsedBytes = "jvm_memory_used_bytes"
  private val MemoryCommittedBytes = "jvm_memory_committed_bytes"
  private val GcSecondsSum = "jvm_gc_collection_seconds_sum"
  private val GcSecondsCount = "jvm_gc_collection_seconds_count"
  private val Threads = "jvm_threads_current"

  /** One parsed sample: a name, its labels as written, and the value.
    *
    * The labels are kept as the raw string between the braces rather than parsed into a map,
    * because the only thing anything here asks of them is whether they say `area="heap"`.
    */
  private case class Sample(name: String, labels: String, value: Double)

  /** The reading, or `None` when the exposition carries no `process_cpu_seconds_total`.
    *
    * That one name is the difference between "a service whose metrics endpoint answered" and "a
    * service that publishes its runtime metrics", and the two have to be distinguishable: a SUT
    * built before `VersolaApp.jvmRuntimeMetrics` serves a perfectly valid `/metrics` full of
    * application counters, and reading zero CPU off it would put a plausible number in the
    * report rather than an absent section.
    */
  def parse(exposition: String): Option[SutProcessReading] =
    val samples = exposition.linesIterator.flatMap(sample).toList
    val byName = samples.groupBy(_.name)

    def first(name: String): Option[Double] = byName.get(name).flatMap(_.headOption).map(_.value)
    def sum(name: String): Double = byName.getOrElse(name, Nil).map(_.value).sum
    def area(name: String, area: String): Double =
      byName.getOrElse(name, Nil).filter(_.labels.contains(s"""area="$area"""")).map(_.value).sum

    first(CpuSeconds).map: cpuSeconds =>
      SutProcessReading(
        // Seconds since the epoch as a float, which is how the exposition states it. Kept at
        // that precision rather than rounded to an Instant: it is compared for equality across
        // the campaign's two boundaries and nothing else, so what matters is that it survives
        // the round trip unchanged.
        startedAtEpochSeconds = first(StartTimeSeconds),
        stats = SutProcessStats(
          counters = SutProcessCounters(
            cpuSeconds = cpuSeconds,
            // Summed over collectors: the exposition publishes one series per collector
            // (`gc="G1 Young Generation"`, `gc="G1 Old Generation"`), and the report's question
            // is what the process spent collecting, not which generation it spent it in.
            gcSeconds = sum(GcSecondsSum),
            gcCollections = sum(GcSecondsCount).toLong,
          ),
          gauges = SutProcessGauges(
            heapUsedBytes = area(MemoryUsedBytes, "heap").toLong,
            heapCommittedBytes = area(MemoryCommittedBytes, "heap").toLong,
            nonHeapUsedBytes = area(MemoryUsedBytes, "nonheap").toLong,
            residentMemoryBytes = first(ResidentMemoryBytes).getOrElse(0.0).toLong,
            threads = first(Threads).getOrElse(0.0).toLong,
            openFileDescriptors = first(OpenFds).getOrElse(0.0).toLong,
          ),
        ),
      )

  /** One sample line, or `None` for a comment, a blank line, or anything this cannot read as a
    * number.
    *
    * A line is `name[{labels}] value [timestamp]`. The value is taken as the *second-to-last*
    * field when a trailing timestamp is present and the last otherwise, and parsed with
    * `toDoubleOption` -- which accepts the `2.6875464E8` scientific notation the publisher emits
    * for large values, and rejects `NaN`-free garbage by returning `None` rather than throwing
    * into a campaign boundary.
    */
  private def sample(line: String): Option[Sample] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then None
    else
      val (name, labels, rest) =
        val brace = trimmed.indexOf('{')
        if brace < 0 then
          val space = trimmed.indexOf(' ')
          if space < 0 then ("", "", "")
          else (trimmed.substring(0, space), "", trimmed.substring(space + 1))
        else
          val close = trimmed.indexOf('}', brace)
          if close < 0 then ("", "", "")
          else (trimmed.substring(0, brace), trimmed.substring(brace + 1, close), trimmed.substring(close + 1))
      val fields = rest.trim.split("\\s+").toList.filter(_.nonEmpty)
      for
        _ <- Option.when(name.nonEmpty)(())
        // The value is the first field after the name: a trailing timestamp follows it, and
        // nothing else may.
        raw <- fields.headOption
        value <- raw.toDoubleOption
        if !value.isNaN
      yield Sample(name, labels, value)

/** A reading plus the process identity it was taken from.
  *
  * `startedAtEpochSeconds` is separate from the statistics for the reason [[SutStats]]'s reset
  * instants are: it is not a measurement of the run, it is what decides whether the run's two
  * measurements may be subtracted. `None` when the exposition omits it, which
  * [[SutProcessStatsDelta]] treats as "cannot prove it did not restart".
  */
case class SutProcessReading(startedAtEpochSeconds: Option[Double], stats: SutProcessStats)
