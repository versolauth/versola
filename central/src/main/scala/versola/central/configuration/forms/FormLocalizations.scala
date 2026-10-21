package versola.central.configuration.forms

/** The rules a form version's texts keep, however they were written: every language has the same keys, and a key has
  * the same `{placeholders}` in every language. The login forms look a key up by name and fill its placeholders from
  * the page's state, so a language that lacks a key, or spells a placeholder differently, breaks that page for its
  * readers only.
  *
  * Which languages a form carries is not decided here. A language need not be registered with the locales, nor active:
  * central stores texts ahead of a language being switched on, and activating a language is checked by
  * `LocaleCompletenessValidator`. So an unknown language is accepted, and is held to the same keys as the others.
  */
object FormLocalizations:
  private val Placeholder = """\{([A-Za-z0-9_]+)\}""".r

  /** Reasons listed at most; the rest would only repeat the same fault in another language. */
  private val MaxReasons = 5

  /** The language the others are compared with: English when present, else the first by code. */
  private def reference(localizations: Map[String, Map[String, String]]): Option[String] =
    if localizations.contains("en") then Some("en") else localizations.keys.toVector.sorted.headOption

  private def placeholders(text: String): Set[String] =
    Placeholder.findAllMatchIn(text).map(_.group(1)).toSet

  private def render(names: Set[String]): String =
    if names.isEmpty then "none" else names.toVector.sorted.map(name => s"{$name}").mkString(", ")

  /** The faults of `localizations`, one sentence each; empty when it is consistent. */
  def faults(localizations: Map[String, Map[String, String]]): Vector[String] =
    reference(localizations) match
      case None => Vector.empty
      case Some(base) =>
        val expected = localizations(base)
        localizations.toVector
          .filter((code, _) => code != base)
          .sortBy((code, _) => code)
          .flatMap: (code, texts) =>
            val missing = (expected.keySet -- texts.keySet).toVector.sorted
              .map(key => s"Locale '$code' has no text for '$key', which '$base' has")
            val extra = (texts.keySet -- expected.keySet).toVector.sorted
              .map(key => s"Locale '$code' has the key '$key', which '$base' lacks")
            val mismatched = (expected.keySet.intersect(texts.keySet)).toVector.sorted.flatMap: key =>
              val want = placeholders(expected(key))
              val have = placeholders(texts(key))
              Option.when(want != have)(
                s"Key '$key' has the placeholders ${render(want)} in '$base' but ${render(have)} in '$code'",
              )
            missing ++ extra ++ mismatched

  /** The texts every language of a form is compared with: its English texts when it has them, else the first language
    * by code; empty for a form that carries no text at all. */
  def referenceTexts(localizations: Map[String, Map[String, String]]): Map[String, String] =
    reference(localizations).flatMap(localizations.get).getOrElse(Map.empty)

  /** The faults of `texts` as the translation of a form into `locale`, held against the form's `reference` texts: a key
    * the form does not have, a blank text, a placeholder spelled differently. A key the translation lacks is no fault
    * here: a translation is filled in over time, and a language is checked as a whole when it is activated. */
  def translationFaults(reference: Map[String, String], locale: String, texts: Map[String, String]): Vector[String] =
    texts.toVector.sortBy((key, _) => key).flatMap: (key, text) =>
      reference.get(key) match
        case None => Vector(s"'$key' is not a text of this form")
        case Some(_) if text.trim.isEmpty => Vector(s"The text for '$key' in '$locale' is blank; leave a key out to remove it")
        case Some(base) =>
          val want = placeholders(base)
          val have = placeholders(text)
          if want == have then Vector.empty
          else Vector(s"Key '$key' has the placeholders ${render(want)} in the form but ${render(have)} in '$locale'")

  /** `Left` with the faults as one sentence, the first few of them, when the languages disagree. */
  def validate(localizations: Map[String, Map[String, String]]): Either[String, Unit] =
    val found = faults(localizations)
    if found.isEmpty then Right(())
    else
      val more = found.size - MaxReasons
      val listed = found.take(MaxReasons).mkString("; ")
      Left(if more > 0 then s"$listed; and $more more" else listed)
