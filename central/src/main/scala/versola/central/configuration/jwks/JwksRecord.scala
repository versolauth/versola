package versola.central.configuration.jwks

import zio.json.ast.Json

/**
 * A single JSON Web Key stored in the central database.
 *
 * `kid` is the key id (primary key); `jwk` is the raw JWK JSON object as served
 * in the central JWKS.
 */
case class JwksRecord(
    kid: String,
    jwk: Json.Obj,
)
