package versola.central.configuration.forms

import zio.Task

trait FormTranslationRepository:
  def getAll: Task[Vector[FormTranslationRecord]]

  /** Replaces the texts of `formId` in `locale`. */
  def upsert(record: FormTranslationRecord): Task[Unit]

  /** `false` when there was nothing to delete. */
  def delete(formId: FormId, locale: String): Task[Boolean]
