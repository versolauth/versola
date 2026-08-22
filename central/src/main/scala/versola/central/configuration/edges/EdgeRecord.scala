package versola.central.configuration.edges

import com.nimbusds.jose.jwk.RSAKey
import versola.util.JWT
import zio.json.EncoderOps
import zio.json.ast.Json

import java.security.interfaces.RSAPublicKey

/**
 * Stored edge - infrastructure/deployment unit.
 *
 * `publicKey` and `oldPublicKey` are JWK JSON objects stored in the database.
 * `oldPublicKey` is populated only during a key rotation window.
 *
 * Edges are infrastructure units. Tenants declare which edge they use.
 * Clients inherit their edge from their tenant.
 *
 * `revocationCacheSize` is how many revocations that edge keeps in memory. It lives here
 * rather than in the edge's own config because it is sized against the traffic an edge
 * sees, which is visible from central and changes without a redeploy.
 */
case class EdgeRecord(
    id: EdgeId,
    publicKey: Json.Obj,
    oldPublicKey: Option[Json.Obj],
    revocationCacheSize: Int,
):
  def asPublicKeys: JWT.PublicKeys =
    val keys = Json.Arr((publicKey +: oldPublicKey.toVector)*)
    JWT.PublicKeys.fromJson(Json.Obj("keys" -> keys))

  def activeRsaPublicKey: RSAPublicKey =
    RSAKey.parse(publicKey.toJson).toRSAPublicKey
