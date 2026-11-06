package versola.loadgen.antifraud

import zio.http.{Client, Request}
import zio.json.*
import zio.{Duration, Ref, Schedule, UIO, ZIO}

/** What `auth` says about the antifraud work it does, read from its diagnostics `/metrics` (the names are
  * `AntifraudMetrics`'s in `AntifraudEvents.scala`).
  *
  * Counters are read twice and subtracted; the queue's size is a gauge, so it is sampled while a scenario runs and
  * its largest value is what matters.
  *
  * @param dropped events dropped, by `reason`
  */
case class AuthAntifraudMetrics(
    enqueued: Double,
    written: Double,
    dropped: Map[String, Double],
    smsRefused: Double,
    smsBudgetFull: Double,
    queueSize: Double,
):
  def minus(before: AuthAntifraudMetrics): AuthAntifraudMetrics =
    AuthAntifraudMetrics(
      enqueued = enqueued - before.enqueued,
      written = written - before.written,
      dropped = (dropped.keySet ++ before.dropped.keySet).map(reason =>
        reason -> (dropped.getOrElse(reason, 0.0) - before.dropped.getOrElse(reason, 0.0)),
      ).toMap,
      smsRefused = smsRefused - before.smsRefused,
      smsBudgetFull = smsBudgetFull - before.smsBudgetFull,
      queueSize = queueSize,
    )

  /** Two instances' figures as one: counters and the queues' sizes (a backlog is the sum of the instances') added. */
  def plus(other: AuthAntifraudMetrics): AuthAntifraudMetrics =
    AuthAntifraudMetrics(
      enqueued = enqueued + other.enqueued,
      written = written + other.written,
      dropped = (dropped.keySet ++ other.dropped.keySet).map(reason => reason -> (dropped.getOrElse(reason, 0.0) + other.dropped.getOrElse(reason, 0.0))).toMap,
      smsRefused = smsRefused + other.smsRefused,
      smsBudgetFull = smsBudgetFull + other.smsBudgetFull,
      queueSize = queueSize + other.queueSize,
    )

  def droppedTotal: Double = dropped.values.sum

object AuthAntifraudMetrics:
  val empty: AuthAntifraudMetrics =
    AuthAntifraudMetrics(enqueued = 0, written = 0, dropped = Map.empty, smsRefused = 0, smsBudgetFull = 0, queueSize = 0)

  given JsonCodec[AuthAntifraudMetrics] = DeriveJsonCodec.gen

  private val Sample = """^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+(\S+)(\s+\d+)?$""".r
  private val Reason = """reason="([^"]*)"""".r

  /** Reads the Prometheus text exposition. A series that is absent counts as zero: a counter that was never
    * incremented is not exported. */
  def parse(text: String): AuthAntifraudMetrics =
    val samples = text.linesIterator.flatMap:
      case Sample(name, _, labels, value, _) => value.toDoubleOption.map(v => (name, Option(labels).getOrElse(""), v))
      case _ => None
    .toList
    def sum(name: String): Double = samples.collect { case (`name`, _, v) => v }.sum
    val dropped = samples.collect { case ("antifraud_events_dropped_total", labels, v) =>
      Reason.findFirstMatchIn(labels).map(_.group(1)).getOrElse("") -> v
    }.groupMapReduce(_._1)(_._2)(_ + _)
    AuthAntifraudMetrics(
      enqueued = sum("antifraud_events_enqueued_total"),
      written = sum("antifraud_events_written_total"),
      dropped = dropped,
      smsRefused = sum("antifraud_sms_budget_refused_total"),
      smsBudgetFull = sum("antifraud_sms_budget_full_total"),
      queueSize = sum("antifraud_event_queue_size"),
    )

  def read(client: Client, url: String): ZIO[Any, Throwable, AuthAntifraudMetrics] =
    ZIO.scoped(client.request(Request.get(url)).flatMap(_.body.asString)).map(parse)

  /** Every instance's metrics, summed: the counters are the deployment's, not one instance's. A url that cannot be read
    * fails the whole read, so that a figure is never the sum of fewer instances than were asked for. */
  def readAll(client: Client, urls: List[String]): ZIO[Any, Throwable, AuthAntifraudMetrics] =
    ZIO.foreach(urls)(read(client, _)).map(_.foldLeft(empty)(_.plus(_)))

/** Samples the queue's size at an interval while a scenario runs. */
final class QueueSampler(max: Ref[Double]):
  def sampleOnce(client: Client, urls: List[String]): UIO[Unit] =
    AuthAntifraudMetrics.readAll(client, urls).flatMap(metrics => max.update(math.max(_, metrics.queueSize))).ignore

  def maxSeen: UIO[Double] = max.get

object QueueSampler:
  /** Runs `body` while sampling; the sampler is stopped with it. */
  def around[R, E, A](client: Client, urls: List[String], every: Duration)(body: ZIO[R, E, A]): ZIO[R, E, (A, Double)] =
    for
      max <- Ref.make(0.0)
      sampler = QueueSampler(max)
      fiber <- sampler.sampleOnce(client, urls).repeat(Schedule.spaced(every)).forkDaemon
      result <- body.ensuring(fiber.interrupt)
      _ <- sampler.sampleOnce(client, urls)
      peak <- sampler.maxSeen
    yield (result, peak)
