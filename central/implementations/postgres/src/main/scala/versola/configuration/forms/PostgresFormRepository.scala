package versola.configuration.forms

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.forms.{BackendProperty, FormId, FormLocale, FormRecord, FormRepository}
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Task, ZLayer}

class PostgresFormRepository(xa: TransactorZIO) extends FormRepository, BasicCodecs:

  private val MaxVersions = 5

  given DbCodec[FormId] = DbCodec.StringCodec.biMap(FormId(_), identity[String])
  given DbCodec[FormLocale] = DbCodec.derived
  given localizationsCodec: DbCodec[Map[String, Map[String, String]]] = jsonBCodec[Map[String, Map[String, String]]]
  given propertiesCodec: DbCodec[Vector[BackendProperty]] = jsonBCodec[Vector[BackendProperty]]

  private case class FormRow(
      id: String,
      version: Int,
      active: Boolean,
      style: String,
      js_source: Option[String],
      js_compiled: Option[String],
      localizations: Map[String, Map[String, String]],
      properties: Vector[BackendProperty],
  ) derives DbCodec

  private def toRecord(form: FormRow): FormRecord =
    FormRecord(
      id = FormId(form.id),
      version = form.version,
      active = form.active,
      style = form.style,
      jsSource = form.js_source,
      jsCompiled = form.js_compiled,
      localizations = form.localizations,
      properties = form.properties,
    )

  override def getAll: Task[Vector[FormRecord]] =
    xa.connect:
      sql"""SELECT id, version, active, style, js_source, js_compiled, localizations, properties FROM forms ORDER BY id, version DESC"""
        .query[FormRow]
        .run()
        .map(toRecord)

  override def find(id: FormId, version: Int): Task[Option[FormRecord]] =
    xa.connect:
      sql"""SELECT id, version, active, style, js_source, js_compiled, localizations, properties FROM forms WHERE id = $id AND version = $version"""
        .query[FormRow]
        .run()
        .headOption
        .map(toRecord)

  override def upsertForm(
      id: FormId,
      style: String,
      jsSource: Option[String],
      jsCompiled: Option[String],
      localizations: Map[String, Map[String, String]],
      properties: Vector[BackendProperty],
      activate: Boolean,
  ): Task[Unit] =
    xa.repeatableRead.transact:
      val versions = sql"""SELECT version FROM forms WHERE id = $id ORDER BY version DESC""".query[Int].run()
      val nextVersion = versions.maxOption.getOrElse(0) + 1
      val makeActive = activate || versions.isEmpty
      if makeActive then
        sql"""UPDATE forms SET active = false WHERE id = $id""".update.run()
      sql"""INSERT INTO forms (id, version, active, style, js_source, js_compiled, localizations, properties)
            VALUES ($id, $nextVersion, $makeActive, $style, $jsSource, $jsCompiled, $localizations, $properties)""".update.run()
      sql"""DELETE FROM forms WHERE id = $id AND active = false AND version NOT IN (
              SELECT version FROM forms WHERE id = $id ORDER BY version DESC LIMIT $MaxVersions
            )""".update.run()
      ()

  override def setActiveVersion(id: FormId, version: Int): Task[Unit] =
    xa.transact:
      sql"""UPDATE forms SET active = false WHERE id = $id""".update.run()
      sql"""UPDATE forms SET active = true WHERE id = $id AND version = $version""".update.run()
    .unit

  override def getLocales: Task[Vector[FormLocale]] =
    xa.connect:
      sql"""SELECT code, name FROM form_locales ORDER BY code"""
        .query[FormLocale]
        .run()

  override def updateLocales(
      add: Vector[FormLocale],
      delete: Vector[String],
  ): Task[Unit] =
    xa.repeatableRead.transact:
      delete.foreach { code =>
        sql"""DELETE FROM form_locales WHERE code = $code""".update.run()
        sql"""UPDATE forms SET localizations = localizations - $code WHERE jsonb_exists(localizations, $code)""".update.run()
      }
      add.foreach { locale =>
        sql"""INSERT INTO form_locales (code, name) VALUES (${locale.code}, ${locale.name})
              ON CONFLICT (code) DO UPDATE SET name = ${locale.name}""".update.run()
      }

object PostgresFormRepository:
  def live: ZLayer[TransactorZIO, Nothing, FormRepository] =
    ZLayer.fromFunction(PostgresFormRepository(_))
