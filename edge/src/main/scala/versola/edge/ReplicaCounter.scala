package versola.edge

import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.net.{Inet4Address, InetAddress}

/** How many edge pods share the traffic, so each enforces its share of a rate limit (`limit / replicas`).
  *
  * Without a source the number is the one the operator wrote in the policy set (`replicas`, default 1). With `replicas-from = "dns:<name>"`
  * in the edge's configuration, the edge resolves `<name>` itself and counts the addresses it gets: the name of a headless Service in
  * Kubernetes (`clusterIP: None`, which lists the ready pods), or the name of a service with several replicas in Docker Compose. That
  * follows an autoscaler without anybody editing a policy, and needs no permission on the Kubernetes API.
  *
  * The count is the last one that was resolved: a failed or empty answer (a DNS hiccup, no pod ready yet) changes nothing, so a glitch cannot
  * make every pod believe it is alone (limits N times looser) or that the group is gone. Before the first answer the policy's number is used.
  */
object ReplicaCounter:
  private val inForce = Metric.gauge("edge_rate_limit_replicas")

  /** The name in `dns:<name>`, or what is wrong with the setting. */
  def parse(spec: String): Either[String, String] =
    spec.trim match
      case s"dns:$name" if name.trim.nonEmpty && !name.exists(c => c.isWhitespace || c == '/') => Right(name.trim)
      case other => Left(s"replicas-from must be dns:<name>, not '$other'")

  /** Pods behind a resolved name: the distinct addresses, counted in one family (IPv4 when there is any), because a dual-stack service
    * lists a pod twice. */
  def count(addresses: List[String]): Int =
    val distinct = addresses.distinct
    val v4 = distinct.filterNot(_.contains(':'))
    if v4.nonEmpty then v4.size else distinct.size

  def resolver(name: String): Task[List[String]] =
    ZIO.attemptBlocking(InetAddress.getAllByName(name).toList.map(_.getHostAddress))

  /** Resolves `name` now and every `interval` for the life of `scope`; reads answer the last good count. */
  def dns(name: String, resolve: String => Task[List[String]], interval: Duration): URIO[Scope, UIO[Option[Int]]] =
    for
      last <- Ref.make(Option.empty[Int])
      failing <- Ref.make(false)
      step =
        resolve(name).either.flatMap:
          case Right(found) if found.nonEmpty =>
            val n = count(found)
            failing.set(false) *> last.getAndSet(Some(n)).flatMap: before =>
              ZIO.logInfo(s"rate limit replicas: $n from dns:$name").when(!before.contains(n))
          case other =>
            val why = other.fold(_.getMessage, _ => "no address")
            // reported once until it recovers, and the last count stays
            failing.getAndSet(true).flatMap(already =>
              ZIO.logWarning(s"rate limit replicas: dns:$name gave no count ($why); keeping the last one").unless(already),
            )
      _ <- (step *> ZIO.sleep(interval)).forever.forkScoped
    yield last.get

  /** The number to apply: the resolved count when there is one, otherwise `configured` (the policy's `replicas`). Publishes which. */
  def effective(resolved: UIO[Option[Int]], configured: UIO[Int]): UIO[Int] =
    for
      r <- resolved
      c <- configured
      n = math.max(1, r.getOrElse(c))
      _ <- inForce.tagged(MetricLabel("source", if r.isDefined then "dns" else "policy")).set(n.toDouble)
    yield n
