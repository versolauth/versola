package versola.central.configuration.jwks

import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.JWT
import versola.util.http.Controller
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{EncoderOps, JsonCodec}
import zio.json.ast.Json
import zio.{Task, ZIO}

/** Endpoints for the central-owned JWKS.
  *
  *   - `GET /configuration/jwks` — admin endpoint that lists the keys.
  *   - `POST /configuration/jwks` — admin endpoint to add a JWK.
  *   - `POST /configuration/jwks/generate` — admin endpoint that generates a keypair for an
  *     `alg` and publishes it, keeping the private half.
  *   - `GET /configuration/jwks/keys` — admin endpoint listing each key's `alg`/`kty` and
  *     whether central can sign with it, for choosing a tenant's signing key.
  *   - `PUT /configuration/jwks` — admin endpoint to update a JWK (matched by `kid`).
  *   - `DELETE /configuration/jwks` — admin endpoint to remove a JWK by `kid` query param.
  *   - `GET /configuration/jwks/sync` — internal endpoint used by auth/edge. Public halves
  *     only: edge verifies, and must never be sent key material it cannot need.
  *   - `GET /configuration/jwks/signing-keys/sync` — internal endpoint auth alone reads,
  *     carrying the encrypted private halves it signs with.
  */
object JwksController extends Controller:
  type Env = Tracing & JwksService & CentralConfig & EdgeService & ResourceService

  def routes: Routes[Env, Throwable] = Routes(
    getJwksEndpoint,
    listKeysEndpoint,
    createJwkEndpoint,
    generateKeyEndpoint,
    updateJwkEndpoint,
    deleteJwkEndpoint,
    getJwksSyncEndpoint,
    getSigningKeysSyncEndpoint,
  )

  val getJwksEndpoint =
    Method.GET / "configuration" / "jwks" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[JwksService]
        jwks <- service.getRaw
      yield Response.json(jwks.toJson)
    }

  val listKeysEndpoint =
    Method.GET / "configuration" / "jwks" / "keys" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[JwksService]
        keys <- service.listKeys
      yield Response.json(KeysResponse(keys).toJson)
    }

  /** The algorithm is the whole request: which keypair to generate, and the `alg` the key is
    * published under. `HS256` is refused by the service -- a shared secret is not a JWKS key.
    */
  val generateKeyEndpoint =
    Method.POST / "configuration" / "jwks" / "generate" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[JwksService]
        algorithm <- request.url.queryZIO[JWT.Algorithm]("alg")
        response <- service.generateKey(algorithm)
          .map(kid => Response.json(GeneratedKey(kid, algorithm.toString).toJson).status(Status.Created))
          .catchSome { case error: JwksService.Error =>
            ZIO.succeed(Response.text(error.message).status(Status.BadRequest))
          }
      yield response
    }

  val createJwkEndpoint =
    Method.POST / "configuration" / "jwks" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[JwksService]
        response <- withValidJwk(request)((kid, jwk) =>
          service.createKey(kid, jwk).as(Response.status(Status.Created)),
        )
      yield response
    }

  val updateJwkEndpoint =
    Method.PUT / "configuration" / "jwks" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[JwksService]
        response <- withValidJwk(request)((kid, jwk) =>
          service.updateKey(kid, jwk).as(Response.status(Status.NoContent)),
        )
      yield response
    }

  val deleteJwkEndpoint =
    Method.DELETE / "configuration" / "jwks" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[JwksService]
        kid <- request.url.queryZIO[String]("kid")
        // A key a tenant still signs with is refused, not a server error: the operator has
        // to move that tenant to another key first.
        response <- service.deleteKey(kid)
          .as(Response.status(Status.NoContent))
          .catchSome { case error: JwksService.Error =>
            ZIO.succeed(Response.text(error.message).status(Status.Conflict))
          }
      yield response
    }

  val getJwksSyncEndpoint =
    Method.GET / "configuration" / "jwks" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[JwksService]
        jwks <- service.getRaw
      yield Response.json(jwks.toJson)
    }

  /** Separate from [[getJwksSyncEndpoint]] rather than a richer payload on it: edge reads that
    * one, and it has no use for a private key. What it never receives, it cannot leak.
    */
  val getSigningKeysSyncEndpoint =
    Method.GET / "configuration" / "jwks" / "signing-keys" / "sync" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[JwksService]
        keys <- service.getSigningKeys
      yield Response.json(SigningKeysResponse(keys).toJson)
    }

  case class KeysResponse(keys: Vector[JwksService.KeySummary]) derives JsonCodec

  case class GeneratedKey(kid: String, alg: String) derives JsonCodec

  /** Encrypted under the shared transport secret `secretKey`, base64url encoded, the same way
    * client secrets already reach auth over this channel.
    */
  case class SigningKeysResponse(privateKeys: Map[String, String]) derives JsonCodec

  /** Parses the request body as a JWK and extracts its `kid`, replying with
    * `400 Bad Request` for malformed JSON or a missing `kid` before invoking
    * `onValid`.
    */
  private def withValidJwk(request: Request)(onValid: (String, Json.Obj) => Task[Response]): Task[Response] =
    request.bodyAs[Json.Obj].either.flatMap:
      case Left(_) =>
        ZIO.succeed(Response.text("Invalid JWK JSON").status(Status.BadRequest))
      case Right(jwk) =>
        jwk.fields.collectFirst { case ("kid", Json.Str(k)) => k } match
          case None =>
            ZIO.succeed(Response.text("JWK must contain a 'kid' field").status(Status.BadRequest))
          case Some(kid) =>
            onValid(kid, jwk)
