package versola.central.configuration.edges

import versola.util.http.{AccessLogPolicy, OverloadPolicy, RateLimitPolicies}
import zio.json.*
import zio.json.ast.Json

import java.time.Instant

/** The policy sections of an edge, in the order they are shown. The key is the name in the API. */
enum PolicySection(val key: String):
  case Overload extends PolicySection("overload")
  case AccessLog extends PolicySection("accessLog")
  case RateLimits extends PolicySection("rateLimits")

object PolicySection:
  def parse(key: String): Option[PolicySection] = values.find(_.key == key)

/** How many times a section has changed, and when it last did. A section never set has no entry: revision 0. */
final case class PolicySectionMeta(revision: Int, updatedAt: Instant) derives JsonCodec

/** What a patch does to one section: nothing, remove it (the edge goes back to its deployment's values, or applies no rate
  * limit), or replace it as a whole. A section is never merged field by field: the body of a section is the section.
  */
enum Change[+A]:
  case Keep
  case Remove
  case Replace(value: A)

/** `PATCH /configuration/edges/policies`: the sections the body names are changed, the others are left as they are.
  *
  * `expectedRevisions` is optional compare-and-set for a client that must not overwrite a change it has not seen: for each
  * section named there, the patch applies only if the section is still at that revision (`409` otherwise). A section not named has
  * no precondition.
  */
final case class EdgePoliciesPatch(
    overload: Change[OverloadPolicy] = Change.Keep,
    accessLog: Change[AccessLogPolicy] = Change.Keep,
    rateLimits: Change[RateLimitPolicies] = Change.Keep,
    expectedRevisions: Map[PolicySection, Int] = Map.empty,
)

object EdgePoliciesPatch:
  /** Reads the body as the API defines it. A section that is absent is kept, `null` removes it, an object replaces it. A key
    * the API does not know is refused, so a misspelled section is an error and not a silent no-op.
    */
  def parse(body: Json.Obj): Either[PolicyPatchError, EdgePoliciesPatch] =
    val known = PolicySection.values.map(_.key).toSet + "expectedRevisions"
    body.fields.map(_._1).find(!known.contains(_)) match
      case Some(unknown) => Left(PolicyPatchError.UnknownSection(unknown))
      case None =>
        def change[A: JsonDecoder](section: PolicySection): Either[PolicyPatchError, Change[A]] =
          body.get(section.key) match
            case None => Right(Change.Keep)
            case Some(Json.Null) => Right(Change.Remove)
            case Some(value) => value.as[A].left.map(PolicyPatchError.Malformed(section, _)).map(Change.Replace(_))
        for
          overload <- change[OverloadPolicy](PolicySection.Overload)
          accessLog <- change[AccessLogPolicy](PolicySection.AccessLog)
          rateLimits <- change[RateLimitPolicies](PolicySection.RateLimits)
          expected <- body.get("expectedRevisions") match
            case None | Some(Json.Null) => Right(Map.empty[PolicySection, Int])
            case Some(obj: Json.Obj) =>
              obj.fields.foldLeft[Either[PolicyPatchError, Map[PolicySection, Int]]](Right(Map.empty)): (acc, field) =>
                for
                  map <- acc
                  section <- PolicySection.parse(field._1).toRight(PolicyPatchError.UnknownSection(field._1))
                  revision <- field._2.as[Int].left.map(PolicyPatchError.Malformed(section, _))
                yield map.updated(section, revision)
            case Some(_) => Left(PolicyPatchError.UnknownSection("expectedRevisions must be an object"))
        yield EdgePoliciesPatch(overload, accessLog, rateLimits, expected)

  /** The record the patch makes of `record`, or what is wrong with it. Pure: the repository holds the row's lock around it. */
  def apply(record: EdgeRecord, patch: EdgePoliciesPatch, now: Instant): Either[PolicyPatchError, EdgeRecord] =
    val meta = record.policyMeta.getOrElse(Map.empty)
    def revision(section: PolicySection) = meta.get(section.key).fold(0)(_.revision)

    // a section is validated, and an empty one is the same as removing it, as before
    def next[A](section: PolicySection, change: Change[A], current: Option[A], valid: A => Either[String, A], empty: A => Boolean): Either[PolicyPatchError, Option[A]] =
      change match
        case Change.Keep => Right(current)
        case Change.Remove => Right(None)
        case Change.Replace(value) =>
          valid(value).left.map(PolicyPatchError.Invalid(section, _)).map(v => Option.unless(empty(v))(v))

    for
      _ <- patch.expectedRevisions.collectFirst { case (section, expected) if revision(section) != expected => section } match
        case Some(section) => Left(PolicyPatchError.Conflict(section, record))
        case None => Right(())
      overload <- next(PolicySection.Overload, patch.overload, record.overloadPolicy, _.validate, _ == OverloadPolicy.empty)
      accessLog <- next(PolicySection.AccessLog, patch.accessLog, record.accessLogPolicy, _.validate, _ == AccessLogPolicy.empty)
      rateLimits <- next(PolicySection.RateLimits, patch.rateLimits, record.rateLimitPolicies, _.validate, _.policies.isEmpty)
    yield
      def bumped[A](section: PolicySection, before: Option[A], after: Option[A], meta: Map[String, PolicySectionMeta]) =
        if before == after then meta else meta.updated(section.key, PolicySectionMeta(revision(section) + 1, now))
      val updatedMeta = bumped(PolicySection.RateLimits, record.rateLimitPolicies, rateLimits,
        bumped(PolicySection.AccessLog, record.accessLogPolicy, accessLog,
          bumped(PolicySection.Overload, record.overloadPolicy, overload, meta)))
      record.copy(
        overloadPolicy = overload,
        accessLogPolicy = accessLog,
        rateLimitPolicies = rateLimits,
        policyMeta = Option.when(updatedMeta.nonEmpty)(updatedMeta),
      )

/** Why a patch was not applied; the API answers `400` for all but [[Conflict]] (`409`). */
enum PolicyPatchError:
  case UnknownSection(key: String)
  case Malformed(section: PolicySection, detail: String)
  case Invalid(section: PolicySection, detail: String)
  case Conflict(section: PolicySection, current: EdgeRecord)

  def message: String = this match
    case UnknownSection(key) => s"unknown section '$key'; the sections are ${PolicySection.values.map(_.key).mkString(", ")}"
    case Malformed(section, detail) => s"${section.key}: $detail"
    case Invalid(section, detail) => s"${section.key}: $detail"
    case Conflict(section, _) => s"${section.key} has changed since it was read"
