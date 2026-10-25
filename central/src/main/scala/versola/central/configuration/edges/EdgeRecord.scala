package versola.central.configuration.edges

import com.nimbusds.jose.jwk.RSAKey
import versola.util.JWT
import versola.util.http.{AccessLogPolicy, OverloadPolicy}
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
 *
 * `groupId` and `groupKind` say which group of edges this one belongs to and who runs that group; the kind is
 * the group's, copied here so the edge cache answers it without a lookup per call. Neither changes after
 * the edge is registered.
 *
 * `overloadPolicy` is what the edge does when asked for more than it can do: a patch over the settings the edge starts
 * with, `None` meaning none set (the deployment's own values). See [[OverloadPolicy]].
 *
 * `accessLogPolicy` is which of its requests the edge writes a log line for, set the same way and independently: a patch
 * over `HTTP_ACCESS_LOG*`, `None` meaning none set. See [[AccessLogPolicy]].
 */
case class EdgeRecord(
    id: EdgeId,
    publicKey: Json.Obj,
    oldPublicKey: Option[Json.Obj],
    requireDpopNonce: Boolean,
    overloadPolicy: Option[OverloadPolicy] = None,
    accessLogPolicy: Option[AccessLogPolicy] = None,
    groupId: EdgeGroupId = EdgeGroup.DefaultId,
    groupKind: EdgeGroupKind = EdgeGroupKind.FirstParty,
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
