package versola.central.configuration.clients

import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.{RedirectUri, StringNewType}
import zio.prelude.Equal
import zio.schema.*
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

type PresetId = PresetId.Type

object PresetId extends StringNewType:
  given JsonCodec[PresetId] = JsonCodec.string.transform(PresetId(_), identity[String])

case class AuthorizationPreset(
    id: PresetId,
    tenantId: TenantId,
    clientId: ClientId,
    description: String,
    redirectUri: RedirectUri,
    scope: Set[ScopeToken],
    responseType: ResponseType,
    uiLocales: Option[List[String]],
    customParameters: Map[String, List[String]],
) derives Schema, CanEqual, Equal

enum ResponseType derives CanEqual, Equal:
  case Code, CodeIdToken

  def asString: String = this match
    case Code => "code"
    case CodeIdToken => "code id_token"

object ResponseType:
  def fromString(s: String): Option[ResponseType] = s match
    case "code" => Some(Code)
    case "code id_token" => Some(CodeIdToken)
    case _ => None

  // Custom Schema that uses string representation instead of enum case names
  given Schema[ResponseType] = Schema[String].transformOrFail(
    s => fromString(s).toRight(s"Invalid response_type: $s"),
    rt => Right(rt.asString)
  )

  given JsonEncoder[ResponseType] = JsonEncoder.string.contramap(_.asString)
  given JsonDecoder[ResponseType] = JsonDecoder.string
    .mapOrFail(s => fromString(s).toRight(s"Invalid response_type: $s"))
