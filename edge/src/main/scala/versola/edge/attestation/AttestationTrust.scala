package versola.edge.attestation

import versola.edge.EdgeConfig
import zio.http.{Client, Request}
import zio.json.*
import zio.json.ast.Json
import zio.metrics.Metric
import zio.{Duration, Ref, Scope, UIO, ZIO, ZLayer, durationInt}

import java.io.{ByteArrayInputStream, FileInputStream, InputStream}
import java.math.BigInteger
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.jdk.CollectionConverters.*

/** What an attestation is trusted against: the roots the two platforms' chains must lead to, and Google's list of Android
  * certificates that have been revoked.
  *
  * The roots ship with edge (the `attestation` resource directory, with their provenance and fingerprints in the README beside them) and can be
  * replaced by files in the configuration, for the day a platform rotates one before a release of edge carries the new one.
  *
  * The revocation list is fetched from Google and kept. A fetch that fails leaves the last list in force, and a list never fetched
  * is empty: an attestation is not refused because Google could not be reached, and the failure is counted
  * (`attestation_revocation_refresh_failures_total`) so that an edge that has never had the list is something an operator is told.
  */
trait AttestationTrust:
  def appleRoots: Set[X509Certificate]
  def androidRoots: Set[X509Certificate]

  /** The list as it stands: whether Google has revoked the certificate with this serial number. */
  def androidRevocation: UIO[BigInteger => Boolean]

object AttestationTrust:

  private val AppleResource = "/attestation/apple-app-attestation-root.pem"
  private val AndroidResource = "/attestation/android-key-attestation-roots.pem"

  private val refreshFailures = Metric.counter("attestation_revocation_refresh_failures_total")

  /** How long the first attestation waits for the first fetch of the list. */
  private val FirstFetchWait = 10.seconds

  /** When a fetch that failed is tried again. */
  private val RetryAfterFailure = 1.minute

  /** A bounded read of what Google serves, which is some hundreds of kilobytes. */
  private val MaxListLength = 8 * 1024 * 1024

  /** For a test, or a deployment that has decided not to ask Google: the given roots and a fixed list. */
  def fixed(apple: Set[X509Certificate], android: Set[X509Certificate], revoked: Set[BigInteger] = Set.empty): AttestationTrust =
    new AttestationTrust:
      override def appleRoots: Set[X509Certificate] = apple
      override def androidRoots: Set[X509Certificate] = android
      override def androidRevocation: UIO[BigInteger => Boolean] = ZIO.succeed(revoked.contains)

  val live: ZLayer[EdgeConfig & Client & Scope, Throwable, AttestationTrust] =
    ZLayer:
      for
        config <- ZIO.service[EdgeConfig]
        client <- ZIO.service[Client]
        trust <- make(config.native.map(_.attestation).getOrElse(EdgeConfig.Attestation()), client)
      yield trust

  /** The trust of the given settings: the roots read now, and the revocation list fetched when an Android attestation first needs it,
    * then kept fresh for as long as the scope lasts. A list that has never been fetched is empty -- an attestation is not refused
    * because Google could not be reached -- and the failure is counted, and retried every minute.
    *
    * Not at start: an edge with no policy that asks for attestation has no use for Google's list, and should not be seen to reach
    * for it (an edge on a restricted network would wait out the fetch's timeout on every start). A fetch that fails is counted and
    * logged, not raised; the first one is waited for, for a short while, so that the first attestation is not checked against an
    * empty list when Google is reachable. */
  def make(
      settings: EdgeConfig.Attestation,
      client: Client,
      retryAfterFailure: Duration = RetryAfterFailure,
  ): ZIO[Scope, Throwable, AttestationTrust] =
    for
      apple <- roots(settings.appleRoots, AppleResource)
      android <- roots(settings.androidRoots, AndroidResource)
      list <- Ref.make(Set.empty[String])
      scope <- ZIO.scope
      _ <- ZIO.logWarning("attestation: the revocation list is switched off, so no Android certificate is treated as revoked")
        .unless(settings.androidRevocationEnabled)
      // Whether the list was fetched; a failure is counted and logged, never raised.
      refresh = fetch(client, settings.androidRevocationUrl.encode).flatMap(list.set).as(true).catchAllCause: cause =>
        refreshFailures.increment *>
          ZIO.logWarningCause("could not refresh Google's list of revoked Android attestation certificates", cause).as(false)
      // The first fetch is shared by everyone who asks while it runs, so that no attestation is checked against a list that is empty
      // only because another request was first. Then the list is kept fresh -- and one that could not be fetched is tried again
      // soon, not after the whole interval, since until it arrives nothing is known to be revoked.
      start <- (refresh.timeout(FirstFetchWait).map(_.getOrElse(false)).flatMap: fetched =>
        ZIO.iterate(fetched)(_ => true)(fetched => ZIO.sleep(if fetched then settings.revocationRefreshInterval else retryAfterFailure) *> refresh)
          .forkIn(scope).unit).memoize
    yield new AttestationTrust:
      override def appleRoots: Set[X509Certificate] = apple
      override def androidRoots: Set[X509Certificate] = android
      override def androidRevocation: UIO[BigInteger => Boolean] =
        // Google's list names a serial in hex or, for the older 64-bit ones, in decimal; a serial is matched in both forms. The two
        // are told apart by length in what Google serves (17 to 20 digits against 30 to 32), so no serial reads as another's.
        start.when(settings.androidRevocationEnabled) *>
          list.get.map(entries => serial => entries.contains(serial.toString) || entries.contains(serial.toString(16)))

  private def roots(path: Option[String], resource: String): ZIO[Any, Throwable, Set[X509Certificate]] =
    ZIO.scoped:
      ZIO.fromAutoCloseable(ZIO.attemptBlocking(open(path, resource))).flatMap: stream =>
        ZIO.attemptBlocking(parse(stream)).filterOrFail(_.nonEmpty)(
          IllegalStateException(s"no certificate in ${path.getOrElse(resource)}"),
        )

  private def open(path: Option[String], resource: String): InputStream =
    path match
      case Some(file) => FileInputStream(file)
      case None =>
        Option(classOf[AttestationTrust].getResourceAsStream(resource))
          .getOrElse(throw IllegalStateException(s"missing resource $resource"))

  private def parse(stream: InputStream): Set[X509Certificate] =
    CertificateFactory.getInstance("X.509").generateCertificates(stream).asScala.collect { case certificate: X509Certificate => certificate }.toSet

  /** The serial numbers of the list's `entries`, as it names them. */
  private def fetch(client: Client, url: String): ZIO[Any, Throwable, Set[String]] =
    ZIO.scoped:
      for
        response <- client.batched(Request.get(url)).timeoutFail(RuntimeException("timed out"))(30.seconds)
        _ <- ZIO.fail(RuntimeException(s"answered ${response.status}")).unless(response.status.isSuccess)
        body <- response.body.asString
        _ <- ZIO.fail(RuntimeException("the list is larger than expected")).when(body.length > MaxListLength)
        parsed <- ZIO.fromEither(body.fromJson[Json]).mapError(RuntimeException(_))
        entries <- ZIO.fromOption(parsed.asObject.flatMap(_.get("entries")).flatMap(_.asObject))
          .orElseFail(RuntimeException("the list has no entries"))
      yield entries.fields.map(_._1).map(_.trim.toLowerCase).toSet
