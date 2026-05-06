package versola.central.configuration.edges

import versola.util.JWT
import zio.json.ast.Json

/**
 * Stored edge - infrastructure/deployment unit.
 *
 * `publicKey` and `oldPublicKey` are JWK JSON objects stored in the database.
 * `oldPublicKey` is populated only during a key rotation window.
 *
 * Edges are infrastructure units. Tenants declare which edge they use.
 * Clients inherit their edge from their tenant.
 */
case class EdgeRecord(
    id: EdgeId,
    publicKey: Json.Obj,
    oldPublicKey: Option[Json.Obj],
):
  def asPublicKeys: JWT.PublicKeys =
    val keys = Json.Arr((publicKey +: oldPublicKey.toVector)*)
    JWT.PublicKeys.fromJson(Json.Obj("keys" -> keys))
