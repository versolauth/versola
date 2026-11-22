package versola.antifraud

import versola.oauth.client.model.TenantId
import versola.user.authorizeInternal
import versola.util.http.{Controller, Observability}
import versola.util.{CoreConfig, Phone}
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}

import java.time.Instant
import java.util.UUID

/** What central asks of the antifraud lists; internal, auth only trusts the internal token.
  *
  * The lists are central's (one for every cell, with who changed them), and this cell copies them
  * ([[AntifraudPolicySync]]). Three things stay here and are asked: whether an entry is valid and what its value is (the
  * rules are [[AntifraudLists]]', and a phone is hashed with this deployment's key), what it would have matched of this
  * cell's events, and how often it has matched in this cell. Whose scope a change is made in and who made it are central's.
  */
object AntifraudListController extends Controller:
  type Env = Tracing & CoreConfig & AntifraudLists & AntifraudEvents

  def routes: Routes[Env, Throwable] = Routes(checkEndpoint, hitsEndpoint)

  /** The value to store for the entry, or 400 with why it may not be listed; with `preview`, also what it would have matched
    * of the last 24 hours of this cell's events. Nothing is stored. */
  val checkEndpoint =
    Method.POST / "antifraud" / "lists" / "check" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[CheckPayload]
        lists <- ZIO.service[AntifraudLists]
        response <- guarded:
          newEntry(body).flatMap: entry =>
            if body.preview then lists.preview(entry).map((value, preview) => CheckResponse(value, Some(preview)))
            else lists.check(entry).map(CheckResponse(_, None))
          .map(checked => Response.json(checked.toJson))
      yield response
    }

  val hitsEndpoint =
    Method.POST / "antifraud" / "lists" / "hits" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        body <- request.bodyAs[HitsPayload]
        lists <- ZIO.service[AntifraudLists]
        response <- guarded(lists.hits(body.ids).map(hits => Response.json(hits.toJson)))
      yield response
    }

  private def guarded[R](effect: ZIO[R, Throwable, Response]): ZIO[R, Throwable, Response] =
    effect.catchSome:
      case e: InvalidListEntry =>
        Observability.setError("invalid_list_entry", Some(e.getMessage)).as(Response.text(e.getMessage).status(Status.BadRequest))

  /** A phone is hashed here, as an event's destination is, so that a `phone_hash` entry can be made from the number. */
  private def newEntry(entry: CheckPayload): ZIO[AntifraudEvents, Throwable, NewListEntry] =
    for
      value <- (entry.value, entry.phone) match
        case (Some(value), None) => ZIO.succeed(value)
        case (None, Some(phone)) if entry.valueType == AntifraudValueType.phone_hash =>
          ZIO.fromEither(Phone.parse(phone)).mapError(_ => InvalidListEntry("phone is not a valid phone number")).flatMap: parsed =>
            ZIO.serviceWithZIO[AntifraudEvents](_.subjectHash(Right(parsed)))
        case (None, Some(_)) => ZIO.fail(InvalidListEntry("phone is for a phone_hash entry only"))
        case _ => ZIO.fail(InvalidListEntry("give either value or phone, not both and not neither"))
    yield NewListEntry(
      listType = entry.listType,
      valueType = entry.valueType,
      value = value,
      tenantId = entry.tenantId,
      expiresAt = entry.expiresAt,
      reason = entry.reason,
      source = entry.source,
      shadow = entry.shadow,
    )

  /** `value` is what is matched (for a `phone_hash` the hash); `phone` replaces it for a `phone_hash`, as a number. `tenantId`
    * and `source` are central's: the tenant from the scope of whoever asked it, and `manual` for a request of its API. */
  case class CheckPayload(
      listType: AntifraudListType,
      valueType: AntifraudValueType,
      value: Option[String],
      phone: Option[String],
      tenantId: Option[TenantId],
      expiresAt: Option[Instant],
      reason: Option[String],
      source: String,
      shadow: Boolean,
      preview: Boolean,
  ) derives JsonCodec

  case class CheckResponse(value: String, preview: Option[ListPreview]) derives JsonCodec

  case class HitsPayload(ids: List[UUID]) derives JsonCodec
