package versola.antifraud

/** The names a rule can be fired under, which is what the `ruleWeights` and `shadowRules` of the settings and the `rules` of a
  * decision are made of; the console lists them and the settings can be checked against them.
  *
  * TODO(replace): this is a stand-in, built from the same enums the rules are built from so that it follows them, until the
  * engine exports its own list (`Antifraud.ruleNames`, which the shadow-mode work adds). Then `names` becomes a reference to
  * that and nothing else changes: this is the only place the endpoint takes the names from.
  *
  * Patterns (see deploy.md): `velocity_<phone|ip|device|asn|prefix>_<1m|10m|1h|24h>`, `velocity_known_user`, and for every
  * kind of value `deny_list:<type>`, `fake_list:<type>`, `allow_list:<type>`, `watch_list:<type>` and `shadow_list:<type>`.
  * A rule that is in `shadowRules` is listed in a decision as `shadow:<name>`, which is not a name of its own.
  */
object AntifraudRuleCatalogue:

  val names: List[String] =
    val velocity = for
      key <- VelocityKey.values.toList
      window <- VelocityWindow.values.toList
    yield VelocityRules.ruleName(key, window)
    val lists = for
      valueType <- AntifraudValueType.values.toList
      prefix <- List("deny", "fake", "allow", "watch", "shadow")
    yield s"${prefix}_list:$valueType"
    (velocity :+ "velocity_known_user") ++ lists
