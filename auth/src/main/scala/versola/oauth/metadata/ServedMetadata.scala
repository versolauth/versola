package versola.oauth.metadata

import versola.util.Dpop
import zio.json.ast.Json

/** The authorization server metadata document as `auth` actually serves it, paired with what
  * that document was read to mean for DPoP proof validation.
  *
  * Both fields are derived once, when the underlying document is fetched -- see
  * `OAuthConfigurationService`'s metadata cache -- rather than on every read. A GET against a
  * document that hasn't changed should cost nothing more than a `Ref` read.
  *
  * @param document what `auth`'s metadata endpoint serves: the stored document with
  *   `dpop_signing_alg_values_supported` normalized to [[dpopSigningAlgorithms]] rather than
  *   whatever the stored record happened to contain.
  * @param dpopSigningAlgorithms RFC 9449 §5.1: the signing algorithms an incoming DPoP proof's
  *   `alg` is checked against -- exactly what [[document]] advertises, so the set clients
  *   discover and the set a proof is held to cannot drift apart.
  */
case class ServedMetadata(document: Json.Obj, dpopSigningAlgorithms: Set[Dpop.Algorithm])

object ServedMetadata:
  /** The algorithm set is [[Dpop.Algorithm.fromMetadata]]'s, and the served document is then
    * normalized to name exactly that set -- so what clients discover here and what `edge`
    * holds a proxied call's proof to, reading the same field off the same document, are one
    * decision rather than two copies of one.
    */
  def derive(stored: Json.Obj): ServedMetadata =
    val algorithms = Dpop.Algorithm.fromMetadata(stored)
    val advertised = Json.Arr(algorithms.toList.map(algorithm => Json.Str(algorithm.toString)).sortBy(_.value)*)
    val document = Json.Obj(
      (stored.fields.filterNot(_._1 == Dpop.Algorithm.MetadataField) :+
        (Dpop.Algorithm.MetadataField -> advertised))*,
    )
    ServedMetadata(document, algorithms)
