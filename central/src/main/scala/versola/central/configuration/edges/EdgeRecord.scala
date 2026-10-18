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
 * `requireDpopNonce` is RFC 9449 §9 at the resource server: whether every proof this edge
 * checks on a proxied call must carry a nonce it issued. Per edge rather than per resource or
 * per endpoint, because a proof's `htm`/`htu` already bind it to one method and URL -- a nonce
 * accepted across one edge's resources buys an attacker nothing that splitting the space would
 * deny. Distinct from a tenant's `require_dpop_nonce`, which governs `auth`'s own endpoints.
 */
case class EdgeRecord(
    id: EdgeId,
    publicKey: Json.Obj,
    oldPublicKey: Option[Json.Obj],
    requireDpopNonce: Boolean,
):
  def asPublicKeys: JWT.PublicKeys =
    val keys = Json.Arr((publicKey +: oldPublicKey.toVector)*)
    JWT.PublicKeys.fromJson(Json.Obj("keys" -> keys))

  def activeRsaPublicKey: RSAPublicKey =
    RSAKey.parse(publicKey.toJson).toRSAPublicKey

object EdgeRecord:
  /** What a newly registered edge requires: edge has demanded a nonce on every proxied call
    * since DPoP landed there, so an edge is registered that way until an operator turns it off
    * ([[EdgeRepository.setRequireDpopNonce]]). Set by the code that registers an edge; the
    * column has no default of its own. */
  val DefaultRequireDpopNonce: Boolean = true
