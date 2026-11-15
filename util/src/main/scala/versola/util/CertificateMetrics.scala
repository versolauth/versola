package versola.util

import versola.util.audit.AuditOutcome
import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.time.Instant

/** Whether the certificates Versola holds are being renewed, and how long they have left (#1001).
  *
  * Every label is a closed set: `kind` is one of [[Kind]], `outcome` one of the audit log's outcomes. Nothing here is labelled by
  * client, edge or serial -- the number of clients is not bounded, so what is exported about the certificates of a kind is an aggregate
  * (see [[Expiry]]), and the certificate that is about to expire is found in the audit log and in the console.
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

  /** What is known of the certificates of one kind at an instant. `inRenewalWindow` counts those whose renewal is due -- the ones the
    * holder should already be renewing -- and `pastRenewalMidpoint` those that are past the middle of that window and still not
    * renewed, which a renewal that works does not leave for long. A certificate that has expired and was not renewed is in both. */
  case class Expiry(tracked: Long, earliestNotAfter: Option[Instant], inRenewalWindow: Long, pastRenewalMidpoint: Long)

  object Expiry:
    val Empty: Expiry = Expiry(tracked = 0, earliestNotAfter = None, inRenewalWindow = 0, pastRenewalMidpoint = 0)

  private val renewals = Metric.counter("certificate_renewals_total")
  private val tracked = Metric.gauge("certificates_tracked")
  private val inWindow = Metric.gauge("certificates_in_renewal_window")
  private val pastMidpoint = Metric.gauge("certificates_past_renewal_midpoint")
  private val earliest = Metric.gauge("certificate_earliest_not_after_timestamp_seconds")
  private val listenerNotAfter = Metric.gauge("listener_certificate_not_after_timestamp_seconds")
  private val listenerLifetime = Metric.gauge("listener_certificate_lifetime_seconds")

  /** The only listener a service terminates TLS on itself: the one that demands a client certificate. */
  val MutualTlsListener = "mutual_tls"

  /** One renewal that was attempted: `success`, or `denied` / `failure` as the audit log records the attempt that did not give a
    * certificate (`denied`: a rule said no; `failure`: something did not work). A pass that could not even look for the certificates that
    * are due is a `failure` of the kind it was looking for. */
  def renewal(kind: Kind, outcome: AuditOutcome): UIO[Unit] =
    renewals.tagged(Set(MetricLabel("kind", kind.label), MetricLabel("outcome", outcome.toString))).increment

  /** Sets the gauges of `kind`. With nothing tracked the count gauges are 0 and the timestamp is 0, which is no end of any certificate:
    * a rule reads it only where `certificates_tracked` is above 0. */
  def expiry(kind: Kind, expiry: Expiry): UIO[Unit] =
    val labels = Set(MetricLabel("kind", kind.label))
    tracked.tagged(labels).set(expiry.tracked.toDouble) *>
      inWindow.tagged(labels).set(expiry.inRenewalWindow.toDouble) *>
      pastMidpoint.tagged(labels).set(expiry.pastRenewalMidpoint.toDouble) *>
      earliest.tagged(labels).set(expiry.earliestNotAfter.fold(0.0)(_.getEpochSecond.toDouble))

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
