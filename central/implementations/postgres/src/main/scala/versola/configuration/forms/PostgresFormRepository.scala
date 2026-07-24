package versola.configuration.forms

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.forms.{BackendProperty, FormId, FormRecord, FormRepository}
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Task, ZLayer, Duration, ZIO}
import zio.duration.*
import java.sql.SQLException
import scala.math._

class PostgresFormRepository(xa: TransactorZIO) extends FormRepository, BasicCodecs:

  private val MaxVersions = 5
  private val MaxRetries = 3
  private val BaseDelayMillis = 10L

  given DbCodec[FormId] = DbCodec.StringCodec.biMap(FormId(_), identity[String])
  given localizationsCodec: DbCodec[Map[String, Map[String, String]]] = jsonBCodec[Map[String, Map[String, String]]]
  given propertiesCodec: DbCodec[Vector[BackendProperty]] = jsonBCodec[Vector[BackendProperty]]
  given DbCodec[FormRecord] = DbCodec.derived

  final case class VersionInfo(maxVersion: Option[Int], exists: Boolean)
  given DbCodec[VersionInfo] = DbCodec.derived

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
      // Get max version and existence flag with FOR UPDATE to prevent concurrent inserts
      val versionInfo = sql"""SELECT MAX(version), COUNT(*) > 0 FROM forms WHERE id = $id FOR UPDATE"""
        .query[(Option[Int], Boolean)]
        .run()
        .head
      val (maxVersionOpt, exists) = versionInfo
      val nextVersion = maxVersionOrDefault(maxVersionOpt) + 1
      val makeActive = activate || !exists

      // If activating this version, deactivate all other versions for this id
      if makeActive then
        sql"""UPDATE forms SET active = false WHERE id = $id""".update.run()

      // Insert the new version with retry logic for concurrent-insert collisions
      def insertWithRetry(attempt: Int): Task[Unit] =
        ZIO.attemptBlocking(
          sql"""INSERT INTO forms (id, version, active, style, js_source, js_compiled, localizations, properties)
                VALUES ($id, $nextVersion, $makeActive, $style, $jsSource, $jsCompiled, $localizations, $properties)""".update.run()
        ).catchSome {
          case e: SQLException if (isUniqueConstraintViolation(e) || isSerializationFailure(e)) && attempt < MaxRetries - 1 =>
            ZIO.sleep((BaseDelayMillis * math.pow(2, attempt).toLong).millis) *> insertWithRetry(attempt + 1)
        }

      insertWithRetry(0)

      // Clean up old versions, keeping only the newest $MaxVersions
      sql"""DELETE FROM forms WHERE id = $id AND active = false AND version NOT IN (
              SELECT version FROM forms WHERE id = $id ORDER BY version DESC LIMIT $MaxVersions
            )""".update.run()
      ()

  private def maxVersionOrDefault(opt: Option[Int]): Int =
    opt.getOrElse(0)

  private def isUniqueConstraintViolation(e: SQLException): Boolean =
    // PostgreSQL unique violation SQLState is 23505
    Option(e.getSQLState).exists(_ == "23505")

  private def isSerializationFailure(e: SQLException): Boolean =
    // PostgreSQL serialization failure SQLState is 40001
    Option(e.getSQLState).exists(_ == "40001")

  override def setActiveVersion(id: FormId, version: Int): Task[Unit] =
    xa.transactMeasured("set-active-form-version"):
      sql"""UPDATE forms SET active = false WHERE id = $id""".update.run()
      sql"""UPDATE forms SET active = true WHERE id = $id AND version = $version""".update.run()
    .unit

object PostgresFormRepository:
  def live: ZLayer[TransactorZIO, Nothing, FormRepository] =
    ZLayer.fromFunction(PostgresFormRepository(_))
