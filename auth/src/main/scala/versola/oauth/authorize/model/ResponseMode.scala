package versola.oauth.authorize.model

import zio.json.{JsonDecoder, JsonEncoder}
import zio.prelude.NonEmptySet

/** How the authorization response is returned to the client: where its parameters are placed
  * on the redirect URI, and whether they travel as a signed JWT.
  *
  * The `.jwt` variants are JARM (JWT Secured Authorization Response Mode): every response
  * parameter -- including the ones an error carries -- moves inside a single `response`
  * parameter holding a JWT signed with the tenant's key, so the client can tell the response
  * apart from one an attacker assembled.
  *
  * The request's `jwt` value is not represented here: it means "the JWT mode this response
  * type would use anyway", so [[ResponseMode.parse]] resolves it to [[QueryJwt]] or
  * [[FragmentJwt]] at the edge. Everything downstream therefore reads an unambiguous mode.
  */
enum ResponseMode:
  case Query
  case Fragment
  case QueryJwt
  case FragmentJwt

  def useFragment: Boolean = this match
    case Fragment | FragmentJwt => true
    case Query | QueryJwt => false

  /** Whether the response parameters are wrapped in a JARM `response` JWT. */
  def isJwt: Boolean = this match
    case QueryJwt | FragmentJwt => true
    case Query | Fragment => false

  /** The `response_mode` request value this mode is named by. `jwt` is deliberately not
    * produced: a resolved mode always names the placement it resolved to.
    */
  def parameterValue: String = this match
    case Query => "query"
    case Fragment => "fragment"
    case QueryJwt => "query.jwt"
    case FragmentJwt => "fragment.jwt"

object ResponseMode:
  val Parameter = "response_mode"

  /** The mode a request that names none is answered in: OIDC Core §3.3 mandates the fragment
    * for any response type that returns an `id_token` from the authorization endpoint, and
    * RFC 6749 §4.1.2 the query for the plain code flow.
    */
  def default(responseType: NonEmptySet[ResponseTypeEntry]): ResponseMode =
    if responseType.contains(ResponseTypeEntry.IdToken) then Fragment else Query

  /** Resolves a `response_mode` value against the response type it was requested with.
    *
    * `None` covers both an unknown value and one this server implements but the response type
    * forbids -- a query mode for a response type OIDC Core §3.3 requires the fragment for,
    * which would put an `id_token` in the URL a browser sends upstream. Neither is answerable,
    * so the caller refuses the request rather than quietly picking another mode.
    */
  def parse(raw: String, responseType: NonEmptySet[ResponseTypeEntry]): Option[ResponseMode] =
    val requiresFragment = responseType.contains(ResponseTypeEntry.IdToken)
    val mode = raw match
      case "query" => Some(Query)
      case "fragment" => Some(Fragment)
      case "query.jwt" => Some(QueryJwt)
      case "fragment.jwt" => Some(FragmentJwt)
      // JARM §2.1: `jwt` asks for the JWT form of whichever mode the response type implies.
      case "jwt" => Some(if requiresFragment then FragmentJwt else QueryJwt)
      case _ => None
    mode.filter(mode => mode.useFragment || !requiresFragment)

  /** What `response_modes_supported` advertises, in the order discovery documents list it. */
  val supported: List[String] = List("query", "fragment", "jwt", "query.jwt", "fragment.jwt")

  given JsonEncoder[ResponseMode] = JsonEncoder.string.contramap(_.parameterValue)

  given JsonDecoder[ResponseMode] = JsonDecoder.string.mapOrFail: raw =>
    ResponseMode.values.find(_.parameterValue == raw).toRight(s"Unknown response_mode: $raw")
