package versola.edge

import versola.edge.partner.Enrolled
import versola.util.http.Observability
import versola.util.{CertificateSubject, EdgeIdentityNames, SecurityService}
import zio.*
import zio.http.{Body, Client, Header, MediaType, Request, URL}
import zio.json.*
import zio.json.ast.Json

import java.nio.file.{Files, Path}
import java.time.{Duration as JavaDuration, Instant}
import java.util.Base64

/** Gets this edge its certificate from central, and keeps it renewed, where `central.identity` says central issues it.
  *
  * The first certificate is traded for the one-time token an operator created for this edge, over central's public
  * name (plain TLS, no client certificate: there is none yet). Every later one is asked for over the mutual-TLS
  * listener with the certificate in hand, before a third of its lifetime is left, with a new key each time. The key
  * is generated here and goes nowhere: central sees a request and signs it, after checking that it names exactly this
  * edge and its group.
  *
  * A renewal that fails keeps the certificate that is still valid and tries again at the next check, so a CA outage
  * inside the renewal window is not an outage of the edge; one that never succeeds ends in an expired certificate and
  * central turning the edge away, which is visible.
  */
trait EdgeIdentityEnrollment

object EdgeIdentityEnrollment:
  /** How often the certificate is looked at. Short against the days a certificate lives, long against the cost. */
  val CheckInterval: Duration = 10.minutes

  val live
      : ZLayer[Scope & Client & EdgeConfig & EdgeCertificateHolder & CentralSyncTokenService & SecurityService, Throwable, EdgeIdentityEnrollment] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[EdgeConfig]
        client <- ZIO.service[Client]
        holder <- ZIO.service[EdgeCertificateHolder]
        tokens <- ZIO.service[CentralSyncTokenService]
        security <- ZIO.service[SecurityService]
        scope <- ZIO.service[Scope]
        enrollment = Impl(config, client, holder, tokens, security)
        _ <- ZIO.foreachDiscard(config.central.identity): _ =>
          enrollment.ensureCertificate *> enrollment.renewing.forkIn(scope)
      yield new EdgeIdentityEnrollment {}

  private case class EnrollRequest(edgeId: String, token: String, csr: String) derives JsonCodec

  /** `signingKey` is the public key a partner edge has generated to replace the one central holds; absent for the other edges. */
  private case class RenewRequest(csr: String, signingKey: Option[Json.Obj] = None) derives JsonCodec

  /** Central's answer: the chain, leaf first. Renewal is always on (#549 decision 8), so nothing else travels with it; a
    * partner's edge is stopped by suspending the partner, which refuses it everything. */
  private case class Issued(certificate: String) derives JsonCodec

  /** Due when a third of the lifetime or less is left. */
  private[edge] def due(notBefore: Instant, notAfter: Instant, now: Instant): Boolean =
    JavaDuration.between(now, notAfter).compareTo(JavaDuration.between(notBefore, notAfter).dividedBy(3)) <= 0

  private[edge] class Impl(
      config: EdgeConfig,
      client: Client,
      holder: EdgeCertificateHolder,
      tokens: CentralSyncTokenService,
      security: SecurityService,
  ):
    private val subject: CertificateSubject =
      EdgeIdentityNames.subject(
        edgeId = config.id,
        groupId = config.central.identity.fold("default")(_.group),
      )

    /** The certificate there has to be before anything is asked of central: the one in the directory if it is still
      * valid, else one traded for the enrollment token. Fails, and the edge does not start, when there is neither: an
      * edge central turns away in every handshake serves nothing. */
    def ensureCertificate: Task[Unit] =
      for
        now <- Clock.instant
        held <- holder.material
        _ <- held.filter(m => now.isBefore(m.leaf.getNotAfter.toInstant) && !now.isBefore(m.leaf.getNotBefore.toInstant)) match
          case Some(_) => ZIO.unit
          case None => enroll
      yield ()

    /** Where the first certificate is asked and the token it is paid with: `central.identity.enrollment`. A partner edge never gets here
      * (it enrolled before its configuration was read, see `PartnerEnrollment`). */
    private def enrollmentSource: Task[(URL, String)] =
      config.central.identity.flatMap(_.enrollment) match
        case Some(EdgeConfig.CentralEnrollment(Some(url), Some(tokenFile), _)) =>
          ZIO.attemptBlocking(Files.readString(Path.of(tokenFile)).trim)
            .mapError(error => RuntimeException(s"cannot read the enrollment token at $tokenFile: ${error.getMessage}"))
            .map(url -> _)
        case _ =>
          ZIO.fail(
            RuntimeException(
              "this edge has no valid certificate and no enrollment configured: create a token with `POST /configuration/edges/enrollment-tokens` " +
                "and set central.identity.enrollment (url and token-file), or restore the identity directory",
            ),
          )

    private def enroll: Task[Unit] =
      for
        source <- enrollmentSource
        (url, token) = source
        generated <- CertificateSubject.generate(subject)
        body = EnrollRequest(edgeId = config.id, token = token, csr = generated.csrPem).toJson
        request = Request.post(url / "edge" / "enroll", Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
        issued <- send(request, client, "enrollment")
        _ <- holder.install(issued.certificate, generated.privateKeyPem)
        _ <- ZIO.logInfo(s"enrolled with central as edge '${config.id}'")
      yield ()

    def renewing: UIO[Nothing] =
      (renewIfDue.catchAll(error => ZIO.logWarning(s"edge certificate renewal failed: ${error.getMessage}")) *>
        ZIO.sleep(CheckInterval)).forever

    private[edge] def renewIfDue: Task[Unit] =
      for
        now <- Clock.instant
        held <- holder.material
        _ <- ZIO.foreachDiscard(held): material =>
          ZIO.when(due(material.leaf.getNotBefore.toInstant, material.leaf.getNotAfter.toInstant, now))(renew)
      yield ()

    /** A partner edge sends a new public signing key with the request for the certificate and switches to it when central has
      * answered: central replaced the key in the same step, so what the edge signs from then on is with the new one. The key is
      * written to `enrolled.json` first, so that a restart finds the one central holds. */
    private def renew: Task[Unit] =
      for
        generated <- CertificateSubject.generate(subject)
        fresh <- ZIO.when(config.mode == EdgeMode.Partner)(security.generateRsaKeyPair)
        token <- tokens.getToken
        body = RenewRequest(generated.csrPem, fresh.map(_.toPublicJwk)).toJson
        request = Request.post(config.central.url / "edge" / "certificate" / "renew", Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        issued <- send(request, tokens.connection(client), "renewal")
        _ <- ZIO.foreachDiscard(fresh)(switchKey)
        _ <- holder.install(issued.certificate, generated.privateKeyPem)
        _ <- ZIO.logInfo(s"renewed the certificate of edge '${config.id}'${if fresh.isDefined then " and its signing key" else ""}")
      yield ()

    private def switchKey(fresh: versola.util.RsaKeyPair): UIO[Unit] =
      val directory = config.central.identity.map(identity => Path.of(identity.directory))
      ZIO.foreachDiscard(directory): directory =>
        Enrolled.read(directory).flatMap(_.fold(ZIO.unit): known =>
          Enrolled.write(
            directory,
            known.copy(keyId = fresh.keyId, privateKey = Base64.getEncoder.encodeToString(fresh.privateKey.getEncoded)),
          )).catchAll(error =>
          ZIO.logError(s"the new signing key of this edge could not be kept in ${directory}: ${error.getMessage}; a restart will not find it"),
        )
      *> tokens.switchKey(fresh.keyId, fresh.privateKey)

    private def send(request: Request, over: Client, what: String): Task[Issued] =
      for
        response <- ZIO.scoped(over.request(request).flatMap(r => r.body.asString.map(r.status -> _)))
        (status, text) = response
        _ <- ZIO.fail(RuntimeException(s"central refused the $what with $status${if text.isEmpty then "" else s": $text"}"))
          .unless(status.isSuccess)
        issued <-
          ZIO.fromEither(text.fromJson[Issued]).mapError(error => RuntimeException(s"central's answer to the $what is not a certificate: $error"))
      yield issued
