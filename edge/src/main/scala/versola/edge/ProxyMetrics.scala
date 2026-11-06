package versola.edge

import versola.edge.model.ResourceId
import zio.*
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.metrics.MetricLabel

/** What edge itself adds to a proxied request.
  *
  * `http_server_request_duration_seconds` for the resource routes is the caller's whole wait: edge's work
  * plus the resource's own answer. Subtracting the backend's time from it afterwards can only be
  * done between averages or between percentiles of two different histograms, neither of which is
  * the overhead of any request. Measured here, per request, the figure is the real one, so its
  * own p99 can be compared with a budget.
  */
object ProxyMetrics:

  /** 0.5 ms to 1 s: the overhead is expected in single milliseconds, and the interesting failure is
    * the tail, so the low buckets are fine and the top one is a clear "something is wrong". */
  private[edge] val boundaries: Boundaries =
    Boundaries.fromChunk(Chunk(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0))

  private val overhead = Metric.histogram("edge_proxy_overhead_seconds", boundaries)

  /** Whether the request waited on auth before it was handed to the resource, which is the largest and the least
    * edge-owned part of the overhead: a refresh when the access token had expired, the `/userinfo` call an
    * endpoint asks for, or nothing. The overhead budget applies to `None` (what edge itself adds); the other two
    * carry auth's latency and are read as that.
    */
  enum AuthCall(val label: String):
    case None extends AuthCall("none")
    case UserInfo extends AuthCall("userinfo")
    case Refresh extends AuthCall("refresh")

  /** Time from the start of a proxied request to the moment it is handed to the resource: token
    * verification, revocation and DPoP checks, permission and rule evaluation, the token lookup at
    * auth, building the upstream request. The wait for the resource's response is not included.
    *
    * Recorded only for requests that reached the resource, so a request refused earlier does not
    * pull the figure down with a near-zero sample. The label is the resource id, which is
    * configuration: it is looked up before this is called, never taken from the caller's path.
    */
  def beforeUpstream(resourceId: ResourceId, authCall: AuthCall, nanos: Long): UIO[Unit] =
    overhead.tagged(Set(MetricLabel("resource", resourceId.toString), MetricLabel("auth_call", authCall.label))).update(nanos / 1e9)
