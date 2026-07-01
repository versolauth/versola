package versola.central.configuration.jwks

import versola.central.configuration.edges.EdgeService
import versola.central.{CentralConfig, authorizeInternal}
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.json.ast.Json
import zio.{Task, ZIO}

/** Endpoints for the central-owned JWKS.
  *
  *   - `GET /configuration/jwks` — admin endpoint that lists the keys.
  *   - `POST /configuration/jwks` — admin endpoint to add a JWK.
  *   - `PUT /configuration/jwks` — admin endpoint to update a JWK (matched by `kid`).
  *   - `DELETE /configuration/jwks` — admin endpoint to remove a JWK by `kid` query param.
  *   - `GET /configuration/jwks/sync` — internal endpoint used by auth/edge.
  */
object JwksController extends Controller:
  type Env = Tracing & JwksService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getJwksEndpoint,
    createJwkEndpoint,
    updateJwkEndpoint,
    deleteJwkEndpoint,
    getJwksSyncEndpoint,
  )

  val getJwksEndpoint =
    Method.GET / "configuration" / "jwks" -> handler { (_: Request) =>
      for
        service <- ZIO.service[JwksService]
        jwks <- service.getRaw
      yield Response.json(jwks.toJson)
    }

  val createJwkEndpoint =
    Method.POST / "configuration" / "jwks" -> handler { (request: Request) =>
      for
        service <- ZIO.service[JwksService]
        response <- withValidJwk(request)((kid, jwk) =>
          service.createKey(kid, jwk).as(Response.status(Status.Created)),
        )
      yield response
    }

  val updateJwkEndpoint =
    Method.PUT / "configuration" / "jwks" -> handler { (request: Request) =>
      for
        service <- ZIO.service[JwksService]
        response <- withValidJwk(request)((kid, jwk) =>
          service.updateKey(kid, jwk).as(Response.status(Status.NoContent)),
        )
      yield response
    }

  val deleteJwkEndpoint =
    Method.DELETE / "configuration" / "jwks" -> handler { (request: Request) =>
      for
        service <- ZIO.service[JwksService]
        kid <- request.url.queryZIO[String]("kid")
        _ <- service.deleteKey(kid)
      yield Response.status(Status.NoContent)
    }

  val getJwksSyncEndpoint =
    Method.GET / "configuration" / "jwks" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[JwksService]
        jwks <- service.getRaw
      yield Response.json(jwks.toJson)
    }

  /** Parses the request body as a JWK and extracts its `kid`, replying with
    * `400 Bad Request` for malformed JSON or a missing `kid` before invoking
    * `onValid`.
    */
  private def withValidJwk(request: Request)(onValid: (String, Json.Obj) => Task[Response]): Task[Response] =
    request.body.asJsonFromCodec[Json.Obj].either.flatMap:
      case Left(_) =>
        ZIO.succeed(Response.text("Invalid JWK JSON").status(Status.BadRequest))
      case Right(jwk) =>
        jwk.fields.collectFirst { case ("kid", Json.Str(k)) => k } match
          case None =>
            ZIO.succeed(Response.text("JWK must contain a 'kid' field").status(Status.BadRequest))
          case Some(kid) =>
            onValid(kid, jwk)
