package versola.edge.partner

import versola.edge.model.{ApplicationType, ClientCredential, ClientId, OAuthClient, PermissionId}
import versola.edge.{CentralSyncTokenService, EdgeCertificateHolder, EdgeConfig, OAuthClientsSyncClient}
import versola.util.http.Observability
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Duration, Task, URLayer, ZIO, ZLayer}

/** The partner's clients from `/edge/partner/sync/clients` (#962 §4), which carries no credential for any of them: a partner client
  * has no secret, and the credential is the edge's own.
  *
  * A client registered `tls_client_auth` is authenticated by the certificate of this edge, which names its group
  * (`urn:versola:edge-group:<group>`, the `san_uri` the registration is bound to, so every replica of the partner's edge is the
  * same client). That certificate is the one central issued and renews, read from the [[EdgeCertificateHolder]] at every sync, so
  * a renewal is in use by the next refresh. A client registered with any other method has no credential here and is dropped
  * with the reason, as the first-party client drops a client it holds none for: `private_key_jwt` would need the partner's own
  * private key, which this version does not take (a gap named in the pull request), and `client_secret` is not allowed to a
  * partner by central.
  */
object PartnerOAuthClientsSyncClient:
  val live: URLayer[Client & EdgeConfig & CentralSyncTokenService & EdgeCertificateHolder, OAuthClientsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  private[partner] val TlsClientAuth = "tls_client_auth"

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      centralSyncTokenService: CentralSyncTokenService,
      certificates: EdgeCertificateHolder,
  ) extends OAuthClientsSyncClient:
    private val ClientsURL = config.syncUrl("clients")

    private def fetch: Task[Response] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ClientsURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(centralSyncTokenService.syncRequest(httpClient, request).flatMap(_.bodyAs[Response]))
      yield response

    override def getAll: Task[Map[ClientId, OAuthClient]] =
      for
        response <- fetch
        held <- certificates.material
        clients <- ZIO.foreach(response.clients)(client => served(client, held))
      yield clients.flatten.map(client => client.id -> client).toMap

    override def getPermissions: Task[Map[ClientId, Set[PermissionId]]] =
      fetch.map(_.clients.map(client => client.id -> client.permissions.map(PermissionId(_))).toMap)

    private def served(client: Record, held: Option[versola.util.PrivateClientCertificate.Material]): Task[Option[OAuthClient]] =
      val credential: Either[String, ClientCredential] =
        if client.authMethod != TlsClientAuth then
          Left(s"authenticates with ${client.authMethod}, which a partner edge does not hold a credential for")
        else held.map(ClientCredential.MutualTls(_)).toRight("this edge holds no certificate yet")
      credential match
        case Right(credential) =>
          ZIO.some(
            OAuthClient(
              id = client.id,
              credential = credential,
              permissions = client.permissions.map(PermissionId(_)),
              accessTokenTtl = client.accessTokenTtl,
              requireSignedRequestObject = client.requireSignedRequestObject,
              requirePushedAuthorizationRequests = client.requirePushedAuthorizationRequests,
              applicationType = client.applicationType,
              redirectUris = client.redirectUris,
            ),
          )
        case Left(reason) => Observability.setError("partner_client_not_served", Some(s"client '${client.id}' $reason")).as(None)

    private case class Record(
        id: ClientId,
        authMethod: String,
        accessTokenTtl: Duration,
        permissions: Set[String],
        requireSignedRequestObject: Boolean,
        requirePushedAuthorizationRequests: Boolean,
        applicationType: ApplicationType,
        redirectUris: Set[String],
    ) derives JsonCodec

    private case class Response(clients: Vector[Record]) derives JsonCodec
