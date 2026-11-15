package versola.util

import versola.util.audit.AuditOutcome
import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.security.cert.{CertificateFactory, X509Certificate}

/** Whether the certificates Versola holds are being renewed, and how long they have left (#1001).
  *
  * Every label is a closed set: `kind` is one of [[Kind]], `outcome` one of the audit log's outcomes. Nothing here is labelled by
  * client, edge or serial -- the number of clients is not bounded, so what is exported about the certificates of a kind is one count, and
  * the certificate behind it is found in the audit log and in the console.
  *
  * The counter and the gauges of the certificates central holds are published by `central` alone. The gauges of a listener's own
  * certificate are published by each service for itself ([[listener]]), told apart by the `service` label.
  */
object CertificateMetrics:

  /** The certificates there are, by who obtains them: `client` central issues and renews itself (`issueEdgeClientCertificate`), `edge`
    * the identity of an edge, which the edge renews by asking central, and `est` a client's, which the client renews by asking auth
    * (RFC 7030). */
  enum Kind(val label: String):
    case Client extends Kind("client")
    case Edge extends Kind("edge")
    case Est extends Kind("est")

  private val renewals = Metric.counter("certificate_renewals_total")
  private val pastMidpoint = Metric.gauge("certificates_past_renewal_midpoint")
  private val listenerNotAfter = Metric.gauge("listener_certificate_not_after_timestamp_seconds")
  private val listenerLifetime = Metric.gauge("listener_certificate_lifetime_seconds")

  /** The only listener a service terminates TLS on itself: the one that demands a client certificate. */
  val MutualTlsListener = "mutual_tls"

  /** One renewal that was attempted: `success`, or `denied` / `failure` as the audit log records the attempt that did not give a
    * certificate (`denied`: a rule said no; `failure`: something did not work). A pass that could not even look for the certificates that
    * are due is a `failure` of the kind it was looking for. */
  def renewal(kind: Kind, outcome: AuditOutcome): UIO[Unit] =
    renewals.tagged(Set(MetricLabel("kind", kind.label), MetricLabel("outcome", outcome.toString))).increment

  /** How many certificates of `kind` are past the middle of the window in which their holder renews them and are still not renewed (an
    * expired one among them): what a renewal that works does not leave for long, and so the sign that it does not work. */
  def pastRenewalMidpoint(kind: Kind, count: Long): UIO[Unit] =
    pastMidpoint.tagged(Set(MetricLabel("kind", kind.label))).set(count.toDouble)

  /** The end and the length of the validity of the certificate `service` serves on `listener`. */
  def listener(service: String, listener: String, leaf: X509Certificate): UIO[Unit] =
    val labels = Set(MetricLabel("service", service), MetricLabel("listener", listener))
    val notAfter = leaf.getNotAfter.toInstant
    listenerNotAfter.tagged(labels).set(notAfter.getEpochSecond.toDouble) *>
      listenerLifetime.tagged(labels).set((notAfter.getEpochSecond - leaf.getNotBefore.toInstant.getEpochSecond).toDouble)

  /** [[listener]] for the leaf (the first certificate) of the PEM file the listener loads. What cannot be read is logged and leaves the
    * gauges unset: the listener itself reads the same file and says what is wrong with it, so this never fails the start. */
  def listenerFromFile(service: String, listener: String, certificatePath: String): UIO[Unit] =
    ZIO.attemptBlocking(Files.readAllBytes(Path.of(certificatePath)))
      .flatMap(bytes => ZIO.attempt(CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).iterator.next))
      .flatMap:
        case leaf: X509Certificate => CertificateMetrics.listener(service, listener, leaf)
        case _ => ZIO.unit
      .catchAll(error =>
        ZIO.logWarning(s"the certificate of the $listener listener at $certificatePath could not be read for its expiry metric: $error"),
      )
