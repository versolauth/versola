package versola.configuration.partners

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.clients.ClientId
import versola.central.configuration.edges.EdgeGroupId
import versola.central.configuration.partners.{
  PartnerAlreadyExists,
  PartnerContact,
  PartnerId,
  PartnerLimits,
  PartnerRecord,
  PartnerRepository,
  PartnerSettings,
  PartnerStatus,
}
import versola.central.configuration.tenants.TenantId
import versola.util.postgres.BasicCodecs
import zio.{IO, Task, ZLayer}

import java.sql.SQLException
import java.time.Instant

class PostgresPartnerRepository(xa: TransactorZIO) extends PartnerRepository, BasicCodecs:
  given DbCodec[PartnerId] = DbCodec.StringCodec.biMap(PartnerId(_), identity[String])
  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[EdgeGroupId] = DbCodec.StringCodec.biMap(EdgeGroupId(_), identity[String])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[PartnerStatus] =
    DbCodec.StringCodec.biMap(code => PartnerStatus.from(code).fold(message => throw IllegalStateException(message), identity), _.code)
  given DbCodec[PartnerContact] = jsonBCodec[PartnerContact]
  given DbCodec[PartnerLimits] = jsonBCodec[PartnerLimits]
  given DbCodec[PartnerSettings] = jsonBCodec[PartnerSettings]
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[PartnerRecord] = DbCodec.derived

  private val columns = SqlLiteral(
    "id, tenant_id, display_name, logo_uri, contact, status, limits, group_id, console_client_id, settings, review_at, created_at, version",
  )

  override def getAll: Task[Vector[PartnerRecord]] =
    xa.connectMeasured("get-all-partners"):
      sql"SELECT $columns FROM partners ORDER BY id".query[PartnerRecord].run()

  override def find(id: PartnerId): Task[Option[PartnerRecord]] =
    xa.connectMeasured("find-partner"):
      sql"SELECT $columns FROM partners WHERE id = $id".query[PartnerRecord].run().headOption

  override def findByGroup(groupId: EdgeGroupId): Task[Option[PartnerRecord]] =
    xa.connectMeasured("find-partner-by-group"):
      sql"SELECT $columns FROM partners WHERE group_id = $groupId".query[PartnerRecord].run().headOption

  override def create(record: PartnerRecord): IO[PartnerAlreadyExists | Throwable, Unit] =
    xa.connectMeasured("create-partner"):
      sql"""
        INSERT INTO partners ($columns)
        VALUES (${record.id}, ${record.tenantId}, ${record.displayName}, ${record.logoUri}, ${record.contact}, ${record.status},
                ${record.limits}, ${record.groupId}, ${record.consoleClientId}, ${record.settings}, ${record.reviewAt}, ${record.createdAt}, ${record.version})
      """.update.run()
    .unit
      .mapError {
        case e if PostgresPartnerRepository.isUniqueViolation(e) => PartnerAlreadyExists(record.id)
        case e: Throwable => e
      }

  override def updateProfile(
      id: PartnerId,
      expectedVersion: Long,
      displayName: String,
      logoUri: Option[String],
      contact: PartnerContact,
      reviewAt: Option[Instant],
  ): Task[Boolean] =
    xa.connectMeasured("update-partner-profile"):
      sql"""
        UPDATE partners
        SET display_name = $displayName, logo_uri = $logoUri, contact = $contact, review_at = $reviewAt, version = version + 1
        WHERE id = $id AND version = $expectedVersion
      """.update.run() > 0

  override def setNameAndLogo(id: PartnerId, displayName: String, logoUri: Option[String]): Task[Boolean] =
    xa.connectMeasured("set-partner-name-and-logo"):
      sql"UPDATE partners SET display_name = $displayName, logo_uri = $logoUri, version = version + 1 WHERE id = $id".update.run() > 0

  override def setStatus(id: PartnerId, status: PartnerStatus): Task[Boolean] =
    xa.connectMeasured("set-partner-status"):
      sql"UPDATE partners SET status = $status, version = version + 1 WHERE id = $id".update.run() > 0

  override def activate(id: PartnerId): Task[Boolean] =
    xa.connectMeasured("activate-partner"):
      sql"UPDATE partners SET status = 'active', version = version + 1 WHERE id = $id AND status = 'onboarding'".update.run() > 0

  override def setLimits(id: PartnerId, limits: PartnerLimits): Task[Boolean] =
    xa.connectMeasured("set-partner-limits"):
      sql"UPDATE partners SET limits = $limits, version = version + 1 WHERE id = $id".update.run() > 0

  override def setSettings(id: PartnerId, expectedVersion: Long, contact: PartnerContact, settings: PartnerSettings): Task[Boolean] =
    xa.connectMeasured("set-partner-settings"):
      sql"""
        UPDATE partners SET contact = $contact, settings = $settings, version = version + 1
        WHERE id = $id AND version = $expectedVersion
      """.update.run() > 0

object PostgresPartnerRepository:
  def live: ZLayer[TransactorZIO, Nothing, PartnerRepository] =
    ZLayer.fromFunction(PostgresPartnerRepository(_))

  private val UniqueViolationSqlState = "23505"

  private def isUniqueViolation(t: Throwable): Boolean = t match
    case sql: SQLException => sql.getSQLState == UniqueViolationSqlState
    case _ => Option(t.getCause).exists(isUniqueViolation)
