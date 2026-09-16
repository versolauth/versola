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
  /** An algorithm the stored document names but [[Dpop.verify]] has no verifier for is dropped
    * rather than advertised, so the served document can never promise one a proof would then be
    * refused for. A document that names the field but nothing recognizable therefore derives to
    * an empty set and DPoP goes unusable: the operator asked for algorithms none of which exist
    * here, and quietly substituting the defaults would accept the very keys they took the
    * trouble to exclude. Only a field that is absent, or too malformed to read an intent off at
    * all, falls back to [[Dpop.Algorithm.Default]].
    */
  def derive(stored: Json.Obj): ServedMetadata =
    val algorithms = stored.get(Dpop.Algorithm.MetadataField) match
      case None => Dpop.Algorithm.Default
      case Some(field) =>
        field.as[Set[String]].toOption
          .fold(Dpop.Algorithm.Default)(_.flatMap(Dpop.Algorithm.fromName))
    val advertised = Json.Arr(algorithms.toList.map(algorithm => Json.Str(algorithm.toString)).sortBy(_.value)*)
    val document = Json.Obj(
      (stored.fields.filterNot(_._1 == Dpop.Algorithm.MetadataField) :+
        (Dpop.Algorithm.MetadataField -> advertised))*,
    )
    ServedMetadata(document, algorithms)
