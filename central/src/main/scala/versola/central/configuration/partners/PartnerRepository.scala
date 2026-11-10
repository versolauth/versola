package versola.central.configuration.partners

import versola.central.configuration.edges.EdgeGroupId
import versola.util.CacheSource
import zio.{IO, Task}

import java.time.Instant

trait PartnerRepository extends CacheSource[Vector[PartnerRecord]]:
  def getAll: Task[Vector[PartnerRecord]]

  def find(id: PartnerId): Task[Option[PartnerRecord]]

  def findByGroup(groupId: EdgeGroupId): Task[Option[PartnerRecord]]

  def create(record: PartnerRecord): IO[PartnerAlreadyExists | Throwable, Unit]

  /** The operator's side of the record. `false` when there is no such partner. */
  def updateProfile(
      id: PartnerId,
      displayName: String,
      logoUri: Option[String],
      contact: PartnerContact,
      reviewAt: Option[Instant],
  ): Task[Boolean]

  def setStatus(id: PartnerId, status: PartnerStatus): Task[Boolean]

  def setLimits(id: PartnerId, limits: PartnerLimits): Task[Boolean]

  /** The partner's own side: who to reach and what its console offers by default. */
  def setSettings(id: PartnerId, contact: PartnerContact, settings: PartnerSettings): Task[Boolean]
