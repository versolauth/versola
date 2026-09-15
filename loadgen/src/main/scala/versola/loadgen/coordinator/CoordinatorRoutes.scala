package versola.loadgen.coordinator

import zio.http.*
import zio.json.{JsonEncoder, EncoderOps, DecoderOps}
import zio.{IO, ZIO}

/** The coordinator's HTTP surface (dev spec §12). Small on purpose: no UI, no pagination, no
  * per-user anything.
  *
  * Mounted by `versola.loadgen.Main` for every role, against an `Option[CoordinatorService]` that
  * only the coordinator role fills in. One set of routes for one binary is the same decision
  * `role` itself is (§2), and the alternative -- a second `VersolaApp` with its own boot sequence
  * -- is the thing `mockapi` had to do for a measured reason and this has none.
  *
  * `POST /drivers/report` is not in §12's table. It is here because two of the five criteria the
  * campaign is judged on cannot reach a coordinator any other way: the latency histograms travel
  * through `vu_metric_snapshots`, but the error taxonomy and the driver-health readings are in no
  * table, and `CampaignReport` requires both. See [[DriverReport]].
  */
object CoordinatorRoutes:

  def routes: Routes[Option[CoordinatorService], Throwable] =
    Routes(
      Method.GET / "plan" -> handler { (_: Request) =>
        served(_.plan.map(ok))
      },
      Method.POST / "campaign" / "start" -> handler { (_: Request) =>
        served(service => respond(service.start))
      },
      Method.POST / "campaign" / "pause" -> handler { (_: Request) =>
        served(service => respond(service.pause))
      },
      Method.POST / "campaign" / "stop" -> handler { (_: Request) =>
        served(service => respond(service.stop))
      },
      Method.POST / "shards" / "rebalance" -> handler { (request: Request) =>
        served: service =>
          decode[RebalanceRequest](request).flatMap:
            case Left(problem) => ZIO.succeed(refused(Status.BadRequest, problem))
            case Right(rebalance) => respond(service.rebalance(rebalance))
      },
      Method.GET / "status" -> handler { (_: Request) =>
        served(_.status.map(ok))
      },
      Method.GET / "report" / string("campaign") -> handler { (campaign: String, _: Request) =>
        served(service => respond(service.report(campaign)))
      },
      Method.POST / "drivers" / "report" -> handler { (request: Request) =>
        served: service =>
          decode[DriverReport](request).flatMap:
            case Left(problem) => ZIO.succeed(refused(Status.BadRequest, problem))
            // Accepted, not Ok, and with no body: a driver posts this on every poll and has
            // nothing to do with the answer. The plan it does act on comes from `GET /plan`.
            case Right(report) =>
              service.acceptDriverReport(report).as(Response.status(Status.Accepted)).catchAll(failed)
      },
    )

  /** Every route is mounted on every role's server, so "this process is not the coordinator" has
    * to be an answer rather than a missing route -- a driver that was pointed at itself by a
    * misconfigured `coordinator.url` should read "not the coordinator here", not a bare 404 that
    * looks like a version skew in the API.
    */
  private def served(
      use: CoordinatorService => ZIO[Any, Throwable, Response],
  ): ZIO[Option[CoordinatorService], Throwable, Response] =
    ZIO.serviceWithZIO[Option[CoordinatorService]]:
      case Some(service) => use(service)
      case None => ZIO.succeed(refused(Status.ServiceUnavailable, "this process is not running the coordinator role"))

  private def respond[A: JsonEncoder](effect: IO[CoordinatorRefusal | Throwable, A]): ZIO[Any, Throwable, Response] =
    effect.map(ok).catchAll(failed)

  private def failed(error: CoordinatorRefusal | Throwable): ZIO[Any, Throwable, Response] =
    error match
      case refusal: CoordinatorRefusal.Conflict => ZIO.succeed(refused(Status.Conflict, refusal.reason))
      case refusal: CoordinatorRefusal.NotFound => ZIO.succeed(refused(Status.NotFound, refusal.reason))
      // Not turned into a 500 here: `VersolaApp` mounts `Observability.handleErrors` in front of
      // these routes, which logs the cause with the request's span before answering. Swallowing it
      // into a status code would lose that.
      case throwable: Throwable => ZIO.fail(throwable)

  private def decode[A](request: Request)(using zio.json.JsonDecoder[A]): ZIO[Any, Throwable, Either[String, A]] =
    request.body.asString.map(_.fromJson[A])

  private def ok[A: JsonEncoder](value: A): Response =
    Response.json(value.toJson)

  private def refused(status: Status, reason: String): Response =
    Response.json(ErrorBody(reason).toJson).status(status)
