package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.authorize.PushedAuthorizationRepository
import versola.oauth.authorize.model.PushedAuthorizationRecord
import versola.oauth.client.model.ClientId
import versola.oauth.model.RequestUriReference
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task, ZLayer}

import java.time.Instant

class PostgresPushedAuthorizationRepository(
    xa: TransactorZIO,
) extends PushedAuthorizationRepository, BasicCodecs:

  private given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  private given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[PushedAuthorizationRecord] = DbCodec.derived[PushedAuthorizationRecord]

  override def create(
      requestUri: MAC.Of[RequestUriReference],
      record: PushedAuthorizationRecord,
      ttl: Duration,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("create-pushed-authorization-request"):
        sql"""
          INSERT INTO pushed_authorization_requests (request_uri, client_id, params, expires_at)
          VALUES (
            $requestUri,
            ${record.clientId},
            ${record.params},
            ${now.plusSeconds(ttl.toSeconds)}
          )
        """.update.run()
    .unit

  override def consume(requestUri: MAC.Of[RequestUriReference]): Task[Option[PushedAuthorizationRecord]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("consume-pushed-authorization-request"):
        sql"""
          DELETE FROM pushed_authorization_requests
          WHERE request_uri = $requestUri AND expires_at > $now
          RETURNING client_id, params
        """.query[PushedAuthorizationRecord].run()
          .headOption

object PostgresPushedAuthorizationRepository:
  def live: ZLayer[TransactorZIO, Throwable, PushedAuthorizationRepository] =
    ZLayer.fromFunction(PostgresPushedAuthorizationRepository(_))
