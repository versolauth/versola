package versola.configuration.forms

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.forms.{BackendProperty, FormId, FormRecord, FormRepository}
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Task, ZLayer}

class PostgresFormRepository(xa: TransactorZIO) extends FormRepository, BasicCodecs:

  private val MaxVersions = 5

  given DbCodec[FormId] = DbCodec.StringCodec.biMap(FormId(_), identity[String])
  given localizationsCodec: DbCodec[Map[String, Map[String, String]]] = jsonBCodec[Map[String, Map[String, String]]]
  given propertiesCodec: DbCodec[Vector[BackendProperty]] = jsonBCodec[Vector[BackendProperty]]
  given DbCodec[FormRecord] = DbCodec.derived

  override def getAll: Task[Vector[FormRecord]] =
    xa.connectMeasured("get-all-forms"):
      sql"""SELECT id, version, active, style, js_source, js_compiled, localizations, properties FROM forms ORDER BY id, version DESC"""
        .query[FormRecord]
        .run()

  override def find(id: FormId, version: Int): Task[Option[FormRecord]] =
    xa.connectMeasured("find-form"):
      sql"""SELECT id, version, active, style, js_source, js_compiled, localizations, properties FROM forms WHERE id = $id AND version = $version"""
        .query[FormRecord]
        .run()
        .headOption

  override def upsertForm(
      id: FormId,
      style: String,
      jsSource: Option[String],
      jsCompiled: Option[String],
      localizations: Map[String, Map[String, String]],
      properties: Vector[BackendProperty],
      activate: Boolean,
  ): Task[Unit] =
    xa.repeatableRead.transactMeasured("upsert-form"):
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
    xa.transactMeasured("set-active-form-version"):
      sql"""UPDATE forms SET active = false WHERE id = $id""".update.run()
      sql"""UPDATE forms SET active = true WHERE id = $id AND version = $version""".update.run()
    .unit

object PostgresFormRepository:
  def live: ZLayer[TransactorZIO, Nothing, FormRepository] =
    ZLayer.fromFunction(PostgresFormRepository(_))
