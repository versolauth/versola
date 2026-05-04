package versola.configuration.clients

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.json.JsonBDbCodec
import versola.central.configuration.clients.{AuthorizationPreset, AuthorizationPresetRepository, ClientId, PresetId, ResponseType}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import versola.util.postgres.BasicCodecs
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}
import zio.{Task, ZLayer}

class PostgresAuthorizationPresetRepository(
    xa: TransactorZIO,
) extends AuthorizationPresetRepository, BasicCodecs:

  given DbCodec[PresetId] = DbCodec.StringCodec.biMap(PresetId(_), identity[String])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[RedirectUri] = DbCodec.StringCodec.biMap(RedirectUri(_), identity[String])
  given DbCodec[ResponseType] = DbCodec.StringCodec.biMap(
    s => ResponseType.fromString(s).getOrElse(throw new IllegalArgumentException(s"Invalid response_type: $s")),
    _.asString
  )

  given JsonEncoder[Map[String, List[String]]] = JsonEncoder.map
  given JsonDecoder[Map[String, List[String]]] = JsonDecoder.map
  given customParamsCodec: DbCodec[Map[String, List[String]]] = jsonBCodec[Map[String, List[String]]]

  given DbCodec[AuthorizationPreset] = DbCodec.derived

  override def find(id: PresetId): Task[Option[AuthorizationPreset]] =
    xa.connect:
      sql"""
        SELECT id, tenant_id, client_id, description, redirect_uri, scope, response_type, ui_locales, custom_parameters
        FROM authorization_presets
        WHERE id = $id
      """
        .query[AuthorizationPreset].run().headOption

  override def replace(tenantId: TenantId, clientId: ClientId, presets: Seq[AuthorizationPreset]): Task[Unit] =
    xa.repeatableRead.transact:
      sql"""DELETE FROM authorization_presets WHERE tenant_id = $tenantId AND client_id = $clientId""".update.run()
      if presets.nonEmpty then
        batchUpdate(presets): preset =>
          sql"""
            INSERT INTO authorization_presets (
              id, tenant_id, client_id, description, redirect_uri, scope, response_type, ui_locales, custom_parameters
            )
            VALUES (
              ${preset.id}, ${preset.tenantId}, ${preset.clientId}, ${preset.description},
              ${preset.redirectUri}, ${preset.scope}, ${preset.responseType}, ${preset.uiLocales}::text[], ${preset.customParameters}::jsonb
            )
          """.update
    .unit

  override def getAll: Task[Vector[AuthorizationPreset]] =
    xa.connect:
      sql"""
        SELECT id, tenant_id, client_id, description, redirect_uri, scope, response_type, ui_locales, custom_parameters
        FROM authorization_presets
        ORDER BY tenant_id, client_id, id
      """
        .query[AuthorizationPreset].run()

object PostgresAuthorizationPresetRepository:
  def live: ZLayer[TransactorZIO, Throwable, AuthorizationPresetRepository] =
    ZLayer.fromFunction(PostgresAuthorizationPresetRepository(_))
