package versola.central.configuration.locales

import versola.central.configuration.challenges.OtpChallengeRepository
import versola.central.configuration.clients.OAuthClientRepository
import versola.central.configuration.forms.FormRepository
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
      OAuthClientRepository & OAuthScopeRepository & FormRepository & OtpChallengeRepository,
      Nothing,
      LocaleCompletenessValidator,
  ] = ZLayer.fromFunction(Impl(_, _, _, _))

  private final case class Impl(
      clients: OAuthClientRepository,
      scopes: OAuthScopeRepository,
      forms: FormRepository,
      otpTemplates: OtpChallengeRepository,
  ) extends LocaleCompletenessValidator:

    override def missing(locale: String): Task[Vector[String]] =
      for
        clientRecords <- clients.getAll
        scopeRecords <- scopes.getAll
        formRecords <- forms.getAll
        otpRecords <- otpTemplates.getAll
      yield
        val clientMissing = clientRecords.flatMap(client => missingValue(s"client '${client.id}' name", client.clientName, locale))
        val scopeMissing = scopeRecords.flatMap: scope =>
          missingValue(s"scope '${scope.id}' description", scope.description, locale) ++
            scope.claims.flatMap(claim => missingValue(s"scope '${scope.id}' claim '${claim.id}' description", claim.description, locale))
        val formMissing = formRecords.filter(_.active).flatMap: form =>
          form.localizations.get(locale) match
            case None => Vector(s"form '${form.id}' v${form.version}")
            case Some(values) =>
              values.collect { case (key, value) if value.trim.isEmpty => s"form '${form.id}' v${form.version} field '$key'" }.toVector
        val otpMissing = otpRecords.flatMap: template =>
          missingValue(s"OTP template '${template.id}' (${template.tenantId})", template.localizations, locale)
        clientMissing ++ scopeMissing ++ formMissing ++ otpMissing

    private def missingValue(label: String, values: Map[String, String], locale: String): Vector[String] =
      if values.get(locale).exists(_.trim.nonEmpty) then Vector.empty else Vector(label)