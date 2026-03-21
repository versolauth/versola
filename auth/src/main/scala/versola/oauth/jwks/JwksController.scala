package versola.oauth.jwks

import versola.util.CoreConfig
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * JWKS (JSON Web Key Set) Endpoint
 * 
 * Exposes public keys used for JWT signature verification.
 * Per OpenID Connect Discovery 1.0 and RFC 7517 (JSON Web Key).
 * 
 * Endpoint: /.well-known/jwks.json
 */
object JwksController extends Controller:
  type Env = Tracing & CoreConfig

  def routes: Routes[Env, Nothing] = Routes(
    jwksEndpoint,
  )

  /**
   * GET /.well-known/jwks.json
   * 
   * Returns the JSON Web Key Set containing public keys for JWT verification.
   * 
   * Response format per RFC 7517:
   * {
   *   "keys": [
   *     {
   *       "kty": "RSA",
   *       "use": "sig",
   *       "kid": "key-id-1",
   *       "alg": "RS256",
   *       "n": "modulus...",
   *       "e": "exponent..."
   *     }
   *   ]
   * }
   */
  val jwksEndpoint =
    Method.GET / ".well-known" / "jwks.json" -> handler { (_: Request) =>
      for
        config <- ZIO.service[CoreConfig]
      yield Response(
        status = Status.Ok,
        body = Body.fromString(config.jwt.publicKeys.toString),
      )
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader(
          Header.CacheControl.Multiple(
            NonEmptyChunk(
              Header.CacheControl.Public,
              Header.CacheControl.MaxAge(86400),
            ),
          ),
        )
    }
