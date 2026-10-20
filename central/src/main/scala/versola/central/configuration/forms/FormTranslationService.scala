package versola.central.configuration.forms

import versola.central.configuration.locales.LocaleService
import zio.{Task, ZIO, ZLayer}

trait FormTranslationService:
  def getAll: Task[Vector[FormTranslationRecord]]

  /** Replaces the texts of a form in a language. Fails with an [[IllegalArgumentException]] naming the reason when the
    * form is unknown, the language is not registered, or a text does not fit the form: see
    * [[FormLocalizations.translationFaults]].
    */
  def put(formId: FormId, locale: String, texts: Map[String, String]): Task[Unit]

  /** Removes a form's translation into a language; the form keeps what its own record carries. */
  def delete(formId: FormId, locale: String): Task[Unit]

object FormTranslationService:
  def live: ZLayer[FormTranslationRepository & FormService & LocaleService, Nothing, FormTranslationService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      repository: FormTranslationRepository,
      forms: FormService,
      locales: LocaleService,
  ) extends FormTranslationService:

    override def getAll: Task[Vector[FormTranslationRecord]] =
      repository.getAll

    override def put(formId: FormId, locale: String, texts: Map[String, String]): Task[Unit] =
      for
        registered <- locales.getAll.map(_.exists(_.code == locale))
        _ <- fail(s"Locale '$locale' is not registered").unless(registered)
        active <- forms.getAllForms.map(_.find(form => form.id == formId && form.active))
        form <- ZIO.fromOption(active).orElse(fail(s"There is no form '$formId'"))
        faults = FormLocalizations.translationFaults(FormLocalizations.referenceTexts(form.localizations), locale, texts)
        _ <- fail(faults.mkString("; ")).when(faults.nonEmpty)
        _ <- repository.upsert(FormTranslationRecord(formId = formId, locale = locale, texts = texts))
      yield ()

    override def delete(formId: FormId, locale: String): Task[Unit] =
      repository.delete(formId, locale).unit

    private def fail(reason: String): ZIO[Any, IllegalArgumentException, Nothing] =
      ZIO.fail(IllegalArgumentException(reason))
