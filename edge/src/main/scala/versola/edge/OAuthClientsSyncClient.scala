package versola.edge

import versola.edge.model.{ClientCredential, ClientId, OAuthClient, PermissionId}
import versola.util.{Base64, CacheSource, PrivateJsonWebKey, Secret, SecurityService}
import zio.json.ast.Json
import zio.http.{Client, Header, Request}
import zio.json.{JsonCodec, DecoderOps}
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Duration, Task, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

trait OAuthClientsSyncClient extends CacheSource[Map[ClientId, OAuthClient]]:
  def getAll: Task[Map[ClientId, OAuthClient]]

object OAuthClientsSyncClient:
  val live: URLayer[Client & EdgeConfig & SecurityService & CentralSyncTokenService, OAuthClientsSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      httpClient: Client,
      config: EdgeConfig,
      securityService: SecurityService,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends OAuthClientsSyncClient:
    private val ClientsURL = config.central.url / "configuration" / "clients" / "sync"

    override def getAll: Task[Map[ClientId, OAuthClient]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(ClientsURL).addHeader(Header.Authorization.Bearer(token))
        response <- ZIO.scoped(httpClient.request(request))
        response <- response.bodyAs[GetOAuthClientsSyncResponse]
        clients <- ZIO.foreach(response.clients)(credentialed)
      yield clients.flatten.map(x => x.id -> x).toMap

    /** The client as edge can act for it, or nothing.
      *
      * A client central sends no credential for is dropped rather than carried without one:
      * every use edge has for a client record is a call it makes as that client, so a record
      * it cannot authenticate with is a record it can only fail on -- and failing at the
      * login that needs it says far less than never offering it.
      */
    private def credentialed(client: SyncOAuthClientRecord): Task[Option[OAuthClient]] =
      for
        signing <- ZIO.foreach(client.edgeSigningKey)(decryptSigningKey)
        secret <- ZIO.foreach(client.secret)(decryptSecret)
        // The key wins where central sent both. It is the stronger credential, and it is the
        // only one that can sign this client's request objects -- picking the secret here
        // would leave a client that registered both unable to state a signed request.
        credential = signing.map(ClientCredential.PrivateKeyJwt(_))
          .orElse(secret.map(ClientCredential.ClientSecret(_)))
      yield credential.map(
        OAuthClient(
          client.id,
          _,
          client.permissions.map(PermissionId(_)),
          client.accessTokenTtl,
          client.requireSignedRequestObject,
          client.requirePushedAuthorizationRequests,
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

    private def decryptSecret(value: String): Task[Secret] =
      for
        encrypted <- ZIO.attempt(Base64.urlDecode(value))
        decrypted <- securityService.decryptRsa(encrypted, config.privateKey)
      yield Secret(decrypted)

    private case class SyncOAuthClientRecord(
        id: ClientId,
        secret: Option[String],
        accessTokenTtl: Duration,
        permissions: Set[String] = Set.empty,
        requireSignedRequestObject: Boolean = false,
        requirePushedAuthorizationRequests: Boolean = false,
        edgeSigningKey: Option[String] = None,
    ) derives JsonCodec

    private case class GetOAuthClientsSyncResponse(
        clients: Vector[SyncOAuthClientRecord],
    ) derives JsonCodec
