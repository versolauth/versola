package versola.util.http

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** What a limit declared on an API or a client counts separately: one bucket per subject, per client, or for everyone who
  * reaches it. Narrower than [[RateLimitDimension]] on purpose: the declaration is read by the owner of an API or a client,
  * who does not need session or `jkt`, and each value here is known at the point the limit runs.
  */
enum DeclaredRateLimitBy(val wire: String):
  case Subject extends DeclaredRateLimitBy("subject")
  case Client extends DeclaredRateLimitBy("client")

  /** One bucket for the endpoint (or the client) as a whole. */
  case None extends DeclaredRateLimitBy("none")

object DeclaredRateLimitBy:
  def parse(value: String): Option[DeclaredRateLimitBy] = values.find(_.wire == value.trim.toLowerCase)

  given JsonCodec[DeclaredRateLimitBy] = JsonCodec.string.transformOrFail(
    raw => parse(raw).toRight(s"by must be one of ${values.map(_.wire).mkString(", ")}"),
    _.wire,
  )
  given Schema[DeclaredRateLimitBy] = Schema[String].transformOrFail(
    raw => parse(raw).toRight(s"by must be one of ${values.map(_.wire).mkString(", ")}"),
    by => Right(by.wire),
  )

/** What a [[DeclaredRateLimit]] is declared on. Its policy name is fixed (it is a metric label, so it never carries the id of
  * the endpoint or the client), and it fixes where on the request path the limit runs.
  */
enum DeclaredOwner(val policyName: String):
  /** A resource endpoint: counted at R3 per key and endpoint template, once the route is matched. */
  case Endpoint extends DeclaredOwner("resource-endpoint")

  /** An OAuth client: counted at R2 for calls carrying a token issued to the client, once the JWT is verified. */
  case Client extends DeclaredOwner("oauth-client")

/** A rate limit declared where the API or the client is declared (levels 2 and 3 of the design in issue #744 section 4), as
  * central stores it on a resource endpoint or on an OAuth client and sends it to the edge. Absent means no limit.
  *
  * It is a policy with the owner's part filled in: the same windows, burst and mode, and the same rules
  * ([[RateLimitPolicy.validate]]). It applies together with the edge's own policies, and a request must pass every one, so
  * the effective limit is the smallest.
  *
  * @param by
  *   what the bucket is keyed on. On an endpoint `none` is one bucket for the endpoint, `client` one per client and `subject`
  *   one per user. On a client `none` (and so `client`, which is the same) is one bucket for the client, `subject` one per user
  *   of it, so `client` is refused there rather than left to mean two things
  */
final case class DeclaredRateLimit(
    limits: List[RateLimitWindow],
    burst: Int = 1,
    by: DeclaredRateLimitBy = DeclaredRateLimitBy.None,
    mode: GuardMode = GuardMode.Shadow,
) derives JsonCodec, Schema:
  import RateLimitPolicy.given

  /** The policy the limiter runs for this declaration. The scope narrows to the endpoint's template for an endpoint, and stays
    * the whole edge for a client (the limiter keeps the declaration's own buckets, so nothing is shared with another owner).
    */
  def toPolicy(owner: DeclaredOwner): RateLimitPolicy =
    val dimension = by match
      case DeclaredRateLimitBy.Subject => List(RateLimitDimension.Subject)
      case DeclaredRateLimitBy.Client => List(RateLimitDimension.Client)
      case DeclaredRateLimitBy.None => Nil
    RateLimitPolicy(
      name = owner.policyName,
      by = dimension,
      scope = owner match
        case DeclaredOwner.Endpoint => RateLimitScope.Endpoint
        case DeclaredOwner.Client => RateLimitScope.EdgeWide
      ,
      limits = limits,
      burst = burst,
      cost = 1,
      mode = mode,
    )

  /** The first thing wrong with it, if anything; central runs this before storing and the edge before applying. */
  def validate(owner: DeclaredOwner): Either[String, DeclaredRateLimit] =
    for
      _ <- RateLimitPolicy.check(
        !(owner == DeclaredOwner.Client && by == DeclaredRateLimitBy.Client),
        "by must be subject or none on an OAuth client: a client's limit is already counted for the client",
      )
      _ <- toPolicy(owner).validate
    yield this

/** One declaration, with the thing it is declared on: the endpoint template (`GET /resources/{id}/path`, the same string the
  * edge keys R3 on) or the client id.
  */
final case class DeclaredLimit(owner: DeclaredOwner, selector: String, limit: DeclaredRateLimit)
