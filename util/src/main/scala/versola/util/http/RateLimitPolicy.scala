package versola.util.http

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** What a rate limit is keyed on. Every value but `Ip` comes from a verified token or a matched route; the edge never keys on
  * an unverified claim.
  */
enum RateLimitDimension(val wire: String):
  case Ip extends RateLimitDimension("ip")
  case Client extends RateLimitDimension("client")
  case Subject extends RateLimitDimension("subject")
  case Session extends RateLimitDimension("session")
  case Jkt extends RateLimitDimension("jkt")

object RateLimitDimension:
  def parse(value: String): Option[RateLimitDimension] = values.find(_.wire == value.trim.toLowerCase)

  given JsonCodec[RateLimitDimension] = JsonCodec.string.transformOrFail(
    raw => parse(raw).toRight(s"dimension must be one of ${values.map(_.wire).mkString(", ")}"),
    _.wire,
  )
  given Schema[RateLimitDimension] = Schema[String].transformOrFail(
    raw => parse(raw).toRight(s"dimension must be one of ${values.map(_.wire).mkString(", ")}"),
    d => Right(d.wire),
  )

/** What a policy counts separately: `EdgeWide` keeps one bucket per key; `Resource` and `Endpoint` keep one per key and
  * resource, or per key and endpoint template (the registered route, never the concrete path).
  */
enum RateLimitScope(val wire: String):
  case EdgeWide extends RateLimitScope("edge-wide")
  case Resource extends RateLimitScope("resource")
  case Endpoint extends RateLimitScope("endpoint")

object RateLimitScope:
  def parse(value: String): Option[RateLimitScope] = values.find(_.wire == value.trim.toLowerCase)

  given JsonCodec[RateLimitScope] = JsonCodec.string.transformOrFail(
    raw => parse(raw).toRight(s"scope must be one of ${values.map(_.wire).mkString(", ")}"),
    _.wire,
  )
  given Schema[RateLimitScope] = Schema[String].transformOrFail(
    raw => parse(raw).toRight(s"scope must be one of ${values.map(_.wire).mkString(", ")}"),
    s => Right(s.wire),
  )

/** Where on the request path a policy is evaluated, which follows from what it is keyed on:
  *
  *   - `Address` (R1): before the token is read, keyed on the source address;
  *   - `Identity` (R2): after the JWT is verified, before the revocation check and DPoP proof recording;
  *   - `Route` (R3): after the endpoint and permissions are resolved, before the user-info call and the upstream.
  */
enum Checkpoint:
  case Address, Identity, Route

/** `limit` requests per `windowSeconds`: the same pair as `RateLimit(maxAttempts, windowSeconds)` in the login throttle, so an
  * operator reads one idea. Several windows in a policy apply together (a short burst limit and a longer sustained one).
  */
final case class RateLimitWindow(limit: Int, windowSeconds: Int) derives JsonCodec, Schema:

  private[http] def validateIn(policy: String): Either[String, Unit] =
    import RateLimitPolicy.*
    for
      _ <- check(limit >= 1 && limit <= MaxCount, s"policy $policy: limit must be between 1 and $MaxCount")
      _ <- check(windowSeconds >= 1 && windowSeconds <= MaxWindowSeconds, s"policy $policy: windowSeconds must be between 1 and $MaxWindowSeconds")
    yield ()

/** One rate limit on traffic: wire form held by central and sent to the edge (central does not store it yet).
  *
  * @param name
  *   stable identifier that appears in metrics and, later, in the response; lower-case letters, digits, `_` and `-`, so a
  *   metric label stays a closed, bounded set
  * @param by
  *   what the bucket is keyed on; empty means one bucket for the whole scope
  * @param limits
  *   every window must pass for a request to pass
  * @param burst
  *   how many requests may arrive together: a client that has been quiet may send this many at once, then the rate applies.
  *   In any interval a key gets at most `burst` plus what the rate allows. `1` is strict spacing. Never above the smallest
  *   `limit`.
  * @param cost
  *   what one request takes from the bucket; heavier for calls that fetch user-info or write. Never above `burst`, or the
  *   request could never pass.
  * @param mode
  *   `off` ignores the policy, `shadow` counts what `enforce` would refuse and refuses nothing, `enforce` refuses
  */
final case class RateLimitPolicy(
    name: String,
    by: List[RateLimitDimension] = Nil,
    scope: RateLimitScope = RateLimitScope.EdgeWide,
    limits: List[RateLimitWindow],
    burst: Int = 1,
    cost: Int = 1,
    mode: GuardMode = GuardMode.Shadow,
) derives JsonCodec, Schema:
  import RateLimitPolicy.*

  /** The first thing wrong with the policy, if anything; central runs this before storing and the edge before applying. */
  def validate: Either[String, RateLimitPolicy] =
    for
      _ <- check(
        NamePattern.matches(name),
        s"name must be 1 to $MaxNameLength characters: a lower-case letter, then lower-case letters, digits, '_' or '-'",
      )
      _ <- check(by.distinct.size == by.size, s"policy $name: by must not repeat a dimension")
      _ <- check(limits.nonEmpty, s"policy $name: limits must have at least one window")
      _ <- check(limits.size <= MaxWindows, s"policy $name: limits must have at most $MaxWindows windows")
      _ <- limits.foldLeft[Either[String, Unit]](Right(()))((acc, w) => acc.flatMap(_ => w.validateIn(name)))
      _ <- check(limits.map(_.windowSeconds).distinct.size == limits.size, s"policy $name: two limits have the same window")
      _ <- check(burst >= 1 && burst <= MaxCount, s"policy $name: burst must be between 1 and $MaxCount")
      _ <- check(burst <= limits.map(_.limit).min, s"policy $name: burst must not exceed the smallest limit")
      _ <- check(cost >= 1 && cost <= MaxCost, s"policy $name: cost must be between 1 and $MaxCost")
      _ <- check(cost <= burst, s"policy $name: cost must not exceed burst, or a request could never pass")
    yield this

  /** Where the policy is evaluated: a scope narrower than the edge is a property of the matched route (R3); a bucket keyed on
    * the address is checked before the token is read (R1); anything else needs a verified token (R2).
    */
  def checkpoint: Checkpoint =
    if scope != RateLimitScope.EdgeWide then Checkpoint.Route
    else if by.contains(RateLimitDimension.Ip) then Checkpoint.Address
    else Checkpoint.Identity

/** The rate limit policies of one edge, as central stores and serves them: the whole set is replaced at once, so the names
  * stay unique and the limit of 64 holds. An empty set is stored as none.
  *
  * `replicas` is how many edge pods share the traffic: each pod enforces `limit / replicas` (and `burst / replicas`, rounded up),
  * so the edge as a whole allows about the limit the operator wrote. As exact as the balancer is even; there is no shared counter.
  */
final case class RateLimitPolicies(policies: List[RateLimitPolicy] = Nil, replicas: Int = 1) derives JsonCodec, Schema:
  def validate: Either[String, RateLimitPolicies] =
    for
      _ <- RateLimitPolicy.check(
        replicas >= 1 && replicas <= RateLimitPolicies.MaxReplicas,
        s"replicas must be between 1 and ${RateLimitPolicies.MaxReplicas}",
      )
      _ <- RateLimitPolicy.validateAll(policies)
    yield this

object RateLimitPolicies:
  val MaxReplicas = 1000
  val empty: RateLimitPolicies = RateLimitPolicies()

  /** The starting set an operator can add to an edge with one click (central serves it, so the console and the API agree).
    * Every policy is in `shadow`: the edge counts what it would refuse, and the operator reads `would_limit` for a week
    * before switching one to `enforce`. The numbers are deliberately generous (a runaway client or user, not a busy one) and
    * are per edge pod:
    *
    *   - `per-client`: one OAuth client may not take the edge over (200 calls a second sustained, bursts of 500);
    *   - `per-user`: one user, short and long window (5 a second sustained, bursts of 60: a page load fires dozens of calls);
    *   - `per-user-endpoint`: one user on one endpoint (2 a second sustained, bursts of 30), the cheap guard against a loop
    *     on a single route.
    */
  val recommended: RateLimitPolicies = RateLimitPolicies(
    List(
      RateLimitPolicy(
        name = "per-client",
        by = List(RateLimitDimension.Client),
        limits = List(RateLimitWindow(limit = 12000, windowSeconds = 60)),
        burst = 500,
      ),
      RateLimitPolicy(
        name = "per-user",
        by = List(RateLimitDimension.Subject),
        limits = List(RateLimitWindow(limit = 300, windowSeconds = 60), RateLimitWindow(limit = 5000, windowSeconds = 3600)),
        burst = 60,
      ),
      RateLimitPolicy(
        name = "per-user-endpoint",
        by = List(RateLimitDimension.Subject),
        scope = RateLimitScope.Endpoint,
        limits = List(RateLimitWindow(limit = 120, windowSeconds = 60)),
        burst = 30,
      ),
    ),
  )

object RateLimitPolicy:
  val MaxNameLength = 48
  val MaxWindows = 8
  val MaxPolicies = 64
  val MaxCount = 1_000_000
  val MaxCost = 1000
  val MaxWindowSeconds = 86_400

  private val NamePattern = s"[a-z][a-z0-9_-]{0,${MaxNameLength - 1}}".r

  private[http] def check(ok: Boolean, message: => String): Either[String, Unit] = if ok then Right(()) else Left(message)

  given JsonCodec[GuardMode] = JsonCodec.string.transformOrFail(
    raw => GuardMode.parse(raw).toRight("mode must be off, shadow or enforce"),
    _.toString.toLowerCase,
  )
  given Schema[GuardMode] = Schema[String].transformOrFail(
    raw => GuardMode.parse(raw).toRight("mode must be off, shadow or enforce"),
    m => Right(m.toString.toLowerCase),
  )

  /** The first thing wrong with the set: a policy that fails [[RateLimitPolicy.validate]], a repeated name, or too many. */
  def validateAll(policies: List[RateLimitPolicy]): Either[String, List[RateLimitPolicy]] =
    for
      _ <- check(policies.size <= MaxPolicies, s"at most $MaxPolicies rate limit policies")
      _ <- policies.foldLeft[Either[String, Unit]](Right(()))((acc, p) => acc.flatMap(_ => p.validate.map(_ => ())))
      _ <- check(policies.map(_.name).distinct.size == policies.size, "two rate limit policies have the same name")
    yield policies
