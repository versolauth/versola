package versola.oauth.est

import versola.oauth.client.CentralSyncTokenService
import versola.util.CoreConfig
import zio.*
import zio.http.{Body, Header, MediaType, Request, Status}
import zio.json.*

import java.time.Instant

/** What central answers an EST request with (see its `ClientEstController`). Central decides everything about what is issued;
  * auth carries the answer back. */
trait EstCentralClient:
  /** The first certificate, for a one-time token. `clientId` is the user name the caller sent, if any. */
  def enroll(token: String, clientId: Option[String], csrPem: String): IO[EstError | Throwable, EstCentralClient.Issued]

  /** A new certificate, for the certificate (leaf, PEM) the client presented on the mutual-TLS listener. */
  def renew(presentedCertificatePem: String, csrPem: String): IO[EstError | Throwable, EstCentralClient.Issued]

object EstCentralClient:
  /** The certificate chain (PEM, leaf first) and, where the client's chain of renewals ends, when. */
  case class Issued(certificate: String, chainEndsAt: Option[Instant]) derives JsonCodec

  val live: URLayer[CoreConfig & CentralSyncTokenService, EstCentralClient] = ZLayer.fromFunction(Impl(_, _))

  class Impl(config: CoreConfig, central: CentralSyncTokenService) extends EstCentralClient:
    private val EnrollUrl = config.central.url / "est" / "enroll"
    private val RenewUrl = config.central.url / "est" / "renew"

    private case class EnrollBody(token: String, clientId: Option[String], csr: String) derives JsonEncoder
    private case class RenewBody(presentedCertificate: String, csr: String) derives JsonEncoder

    override def enroll(token: String, clientId: Option[String], csrPem: String): IO[EstError | Throwable, Issued] =
      post(EnrollUrl, EnrollBody(token, clientId, csrPem).toJson)

    override def renew(presentedCertificatePem: String, csrPem: String): IO[EstError | Throwable, Issued] =
      post(RenewUrl, RenewBody(presentedCertificatePem, csrPem).toJson)

    private def post(url: zio.http.URL, json: String): IO[EstError | Throwable, Issued] =
      ZIO.scoped:
        for
          response <- central.syncRequest(
            Request.post(url, Body.fromString(json)).addHeader(Header.ContentType(MediaType.application.json)),
          )
          issued <- response.status match
            case Status.Ok => response.body.asJsonFromCodec[Issued]
            case Status.Unauthorized => ZIO.fail(EstError.Unauthorized)
            case Status.Forbidden => response.body.asString.flatMap(reason => ZIO.fail(EstError.Forbidden(reason)))
            case Status.BadRequest => response.body.asString.flatMap(reason => ZIO.fail(EstError.BadRequest(reason)))
            case other => ZIO.fail(RuntimeException(s"central answered the EST request with $other"))
        yield issued
