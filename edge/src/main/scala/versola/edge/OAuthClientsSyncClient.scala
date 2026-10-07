package versola.edge

import versola.edge.model.{ApplicationType, ClientCredential, ClientId, OAuthClient, PermissionId}
import versola.util.{Base64, CacheSource, CertificateSubject, PrivateClientCertificate, PrivateJsonWebKey, Secret, SecurityService}
import zio.json.ast.Json
import zio.http.{Client, Header, Request}
import zio.json.{JsonCodec, DecoderOps}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Duration, Task, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

trait OAuthClientsSyncClient extends CacheSource[Map[ClientId, OAuthClient]]:
  def getAll: Task[Map[ClientId, OAuthClient]]

  /** Every client's permissions, including clients edge holds no credential for. */
  def getPermissions: Task[Map[ClientId, Set[PermissionId]]]

/** Permissions of every client central syncs, whether or not edge holds a credential for it. */
trait ClientPermissionsSyncClient extends CacheSource[Map[ClientId, Set[PermissionId]]]

object ClientPermissionsSyncClient:
  val live: URLayer[OAuthClientsSyncClient, ClientPermissionsSyncClient] =
    ZLayer.fromFunction((source: OAuthClientsSyncClient) =>
      new ClientPermissionsSyncClient:
        def getAll = source.getPermissions,
    )

object OAuthClientsSyncClient:
  val live: URLayer[Client & EdgeConfig & SecurityService & CentralSyncTokenService & ClientCertificateEnrollment, OAuthClientsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
      enrollment: ClientCertificateEnrollment,
  ) extends OAuthClientsSyncClient:
    private val ClientsURL = config.central.url / "configuration" / "clients" / "sync"

    private def fetch: Task[GetOAuthClientsSyncResponse] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ClientsURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        response <- response.bodyAs[GetOAuthClientsSyncResponse]
      yield response

    override def getAll: Task[Map[ClientId, OAuthClient]] =
      for
        response <- fetch
        clients <- ZIO.foreach(response.clients)(credentialed)
      yield clients.flatten.map(x => x.id -> x).toMap

    override def getPermissions: Task[Map[ClientId, Set[PermissionId]]] =
      fetch.map(_.clients.map(c => c.id -> c.permissions.map(PermissionId(_))).toMap)

    /** The client as edge can act for it, or nothing.
      *
      * A client central sends no credential for is dropped rather than carried without one:
      * every use edge has for a client record is a call it makes as that client, so a record
      * it cannot authenticate with is a record it can only fail on -- and failing at the
      * login that needs it says far less than never offering it.
      */
    private def credentialed(client: SyncOAuthClientRecord): Task[Option[OAuthClient]] =
      for
        supplied <- ZIO.foreach(client.edgeClientCertificate)(decryptCertificate)
        // A client enrolled to have this edge generate the key (#463) has no certificate sent: the
        // edge makes its own. One it cannot get is a client it cannot act for, dropped with the
        // reason like any other without a credential -- the rest of the snapshot is unaffected.
        enrolled <- supplied match
          case None =>
            ZIO.foreach(client.edgeCertificateSubject)(subject =>
              enrollment.certificateFor(client.id, subject).map(Some(_)).catchAll: error =>
                ZIO.logError(s"no certificate for client '${client.id}': $error").as(None),
            ).map(_.flatten)
          case Some(_) => ZIO.none
        certificate = supplied.orElse(enrolled)
        signing <- ZIO.foreach(client.edgeSigningKey)(decryptSigningKey)
        secret <- ZIO.foreach(client.secret)(decryptSecret)
        // The strongest credential central sent wins, and for an mTLS client that is the only
        // one auth accepts at all: a certificate registration makes `mtlsAuth` the method, and
        // an assertion or a secret from such a client is refused. Between the other two the key
        // wins for its own reason -- it is the only one that can sign this client's request
        // objects, so picking the secret would leave a client that registered both unable to
        // state a signed request.
        credential = certificate.map(ClientCredential.MutualTls(_))
          .orElse(signing.map(ClientCredential.PrivateKeyJwt(_)))
          .orElse(secret.map(ClientCredential.ClientSecret(_)))
      yield credential.map(
        OAuthClient(
          client.id,
          _,
          client.permissions.map(PermissionId(_)),
          client.accessTokenTtl,
          client.requireSignedRequestObject,
          client.requirePushedAuthorizationRequests,
          client.applicationType,
          client.redirectUris,
        ),
      )

    /** The signing key central encrypted to this edge, parsed into what a signer needs.
      *
      * A key that does not parse fails the whole sync rather than dropping the one client:
      * central validates the document at registration, so an unreadable one here means the
      * two disagree about what was stored, and continuing would serve every other client from
      * a snapshot whose provenance is in doubt.
      */
    private def decryptSigningKey(value: String): Task[PrivateJsonWebKey.Signing] =
      for
        decrypted <- decryptSecret(value)
        document <- ZIO.fromEither(String(decrypted, StandardCharsets.UTF_8).fromJson[Json.Obj])
          .mapError(error => RuntimeException(s"edge signing key is not a JSON object: $error"))
        signing <- ZIO.fromEither(PrivateJsonWebKey(document).signing)
          .mapError(reason => RuntimeException(s"edge signing key $reason"))
      yield signing

    /** The certificate central encrypted to this edge, parsed into what presenting it needs.
      *
      * Fails the whole sync rather than dropping the one client, on the same terms as the
      * signing key: registration validated this PEM, so one that will not parse here means
      * central and this edge disagree about what was stored.
      */
    private def decryptCertificate(value: String): Task[PrivateClientCertificate.Material] =
      for
        decrypted <- decryptSecret(value)
        material <- ZIO
          .fromEither(PrivateClientCertificate(String(decrypted, StandardCharsets.UTF_8)).material)
          .mapError(reason => RuntimeException(s"edge client certificate $reason"))
      yield material

    private def decryptSecret(value: String): Task[Secret] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        // Hybrid, matching ClientController's transportEncrypt: a generated client secret
        // fits in one RSA-OAEP block, but edgeSigningKey's stored JWK document does not, and
        // this decrypts both.
        decrypted <- securityService.decryptRsaHybrid(encrypted, config.privateKey)
      yield Secret(decrypted)

    private case class SyncOAuthClientRecord(
        id: ClientId,
        secret: Option[String],
        accessTokenTtl: Duration,
        permissions: Set[String] = Set.empty,
        requireSignedRequestObject: Boolean = false,
        requirePushedAuthorizationRequests: Boolean = false,
        edgeSigningKey: Option[String] = None,
        edgeClientCertificate: Option[String] = None,
        applicationType: ApplicationType = ApplicationType.web,
        redirectUris: Set[String] = Set.empty,
        edgeCertificateSubject: Option[CertificateSubject] = None,
    ) derives JsonCodec

    private case class GetOAuthClientsSyncResponse(
        clients: Vector[SyncOAuthClientRecord],
    ) derives JsonCodec
