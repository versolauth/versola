package versola.central.configuration.locales

import versola.central.configuration.challenges.OtpChallengeRepository
import versola.central.configuration.clients.OAuthClientRepository
import versola.central.configuration.forms.{FormLocalizations, FormRepository, FormTranslationRepository}
import versola.central.configuration.scopes.OAuthScopeRepository
import zio.{Task, ZIO, ZLayer}

/** Reads all centrally managed localized records and reports missing values for one locale. */
trait LocaleCompletenessValidator:
  def missing(locale: String): Task[Vector[String]]

object LocaleCompletenessValidator:
  val empty: LocaleCompletenessValidator =
    new LocaleCompletenessValidator:
      override def missing(locale: String): Task[Vector[String]] = ZIO.succeed(Vector.empty)

  def live: ZLayer[
    OAuthClientRepository & OAuthScopeRepository & FormRepository & FormTranslationRepository & OtpChallengeRepository,
    Nothing,
    LocaleCompletenessValidator,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _))

  private final case class Impl(
      clients: OAuthClientRepository,
      scopes: OAuthScopeRepository,
      forms: FormRepository,
      formTranslations: FormTranslationRepository,
      otpTemplates: OtpChallengeRepository,
  ) extends LocaleCompletenessValidator:

    override def missing(locale: String): Task[Vector[String]] =
      for
        clientRecords <- clients.getAll
        scopeRecords <- scopes.getAll
        formRecords <- forms.getAll
        translations <- formTranslations.getAll
        otpRecords <- otpTemplates.getAll
      yield
        val clientMissing = clientRecords.flatMap(client => missingValue(s"client '${client.id}' name", client.clientName, locale))
        val scopeMissing = scopeRecords.flatMap: scope =>
          missingValue(s"scope '${scope.id}' description", scope.description, locale) ++
            scope.claims.flatMap(claim => missingValue(s"scope '${scope.id}' claim '${claim.id}' description", claim.description, locale))
        // A form's texts in the language are its own record's with the stored translation laid over, as it is served.
        val formMissing = formRecords.filter(_.active).flatMap: form =>
          val stored = translations.find(t => t.formId == form.id && t.locale == locale).map(_.texts).getOrElse(Map.empty)
          val texts = form.localizations.getOrElse(locale, Map.empty) ++ stored
          if texts.isEmpty then Vector(s"form '${form.id}' v${form.version}")
          else
            val absent = (FormLocalizations.referenceTexts(form.localizations).keySet -- texts.keySet).toVector.sorted
              .map(key => s"form '${form.id}' v${form.version} field '$key'")
            val blank = texts.collect { case (key, value) if value.trim.isEmpty => s"form '${form.id}' v${form.version} field '$key'" }.toVector
            absent ++ blank
        val otpMissing = otpRecords.flatMap: template =>
          missingValue(s"OTP template '${template.id}' (${template.tenantId})", template.localizations, locale)
        clientMissing ++ scopeMissing ++ formMissing ++ otpMissing

    private def missingValue(label: String, values: Map[String, String], locale: String): Vector[String] =
      if values.get(locale).exists(_.trim.nonEmpty) then Vector.empty else Vector(label)
