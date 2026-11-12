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

  /** The operator's side of the record. `false` when there is no such partner, or its row is no longer at `expectedVersion`
    * (somebody wrote it since it was read). */
  def updateProfile(
      id: PartnerId,
      expectedVersion: Long,
      displayName: String,
      logoUri: Option[String],
      contact: PartnerContact,
      reviewAt: Option[Instant],
  ): Task[Boolean]

  /** The name and logo alone, as an approved profile change sets them: the contact and the review date are not touched, so a
    * change the partner asked for a while ago does not overwrite what either side has written since. */
  def setNameAndLogo(id: PartnerId, displayName: String, logoUri: Option[String]): Task[Boolean]

  def setStatus(id: PartnerId, status: PartnerStatus): Task[Boolean]

  /** `onboarding` becomes `active` and nothing else does: `false` when the partner is in any other status, as stored now
    * (a stale read of `onboarding` must not undo a suspension). */
  def activate(id: PartnerId): Task[Boolean]

  def setLimits(id: PartnerId, limits: PartnerLimits): Task[Boolean]

  /** The partner's own side: who to reach and what its console offers by default. `false` when there is no such partner, or its
    * row is no longer at `expectedVersion`. */
  def setSettings(id: PartnerId, expectedVersion: Long, contact: PartnerContact, settings: PartnerSettings): Task[Boolean]
