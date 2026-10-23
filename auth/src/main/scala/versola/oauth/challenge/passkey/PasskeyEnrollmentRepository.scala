package versola.oauth.challenge.passkey

import versola.oauth.client.model.PasskeyEnrollmentState
import versola.user.model.UserId
import zio.Task

import java.time.Instant

/** What `auth` remembers of the passkey offers made to each user, for the tenant's grace period
  * ([[versola.oauth.client.model.PasskeyPolicy]]).
  */
trait PasskeyEnrollmentRepository:
  def find(userId: UserId): Task[Option[PasskeyEnrollmentState]]

  /** Records the first offer made to the user; a later call keeps the first time. */
  def recordOffer(userId: UserId, now: Instant): Task[Unit]

  /** Counts one skipped offer, atomically, so two skips in flight are two skips. */
  def recordSkip(userId: UserId, now: Instant): Task[Unit]
