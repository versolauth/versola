package versola.oauth.client

import versola.oauth.client.model.FormRecord
import versola.util.{CacheSource, CoreConfig}
import zio.http.{Client, Header, Request}
import zio.json.JsonCodec
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec
import zio.{Task, URLayer, ZIO, ZLayer}

trait FormSyncClient extends CacheSource[Vector[FormRecord]]:
  def getAll: Task[Vector[FormRecord]]

object FormSyncClient:
  val live: URLayer[Client & CoreConfig & CentralSyncTokenService, FormSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      httpClient: Client,
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
  ) extends FormSyncClient:
    private val FormsURL = config.central.url / "configuration" / "forms" / "sync"

    override def getAll: Task[Vector[FormRecord]] =
      for
        token <- centralSyncTokenService.getToken
        request = Request.get(FormsURL).addHeader(Header.Authorization.Bearer(token))
        forms <- ZIO.scoped:
          httpClient.request(request).flatMap(_.bodyAs[FormsResponse])
      yield forms.forms

  case class FormsResponse(forms: Vector[FormRecord]) derives JsonCodec
