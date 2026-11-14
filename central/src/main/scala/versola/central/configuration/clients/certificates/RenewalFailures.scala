package versola.central.configuration.clients.certificates

import versola.central.configuration.clients.ClientId
import zio.{Ref, UIO}

/** Which clients' automatic renewals are failing, and why, so that the audit log gets one event when a renewal starts to fail and
  * not one for every pass (`renewDue` repeats every `checkInterval`) while an issuer is down.
  *
  * What is kept is one reason code per client whose renewal failed on the last pass it was due in: [[retainOnly]] drops every other
  * client, so it never holds more than the managed certificates that are due, and a renewal that succeeds, a client that is deleted
  * and a certificate an operator replaced all leave it. It is memory of this process: after a restart the first failure of each
  * client is news again. */
final class RenewalFailures(state: Ref[Map[ClientId, String]]):

  /** Records that the renewal of `clientId` failed for `reason`, and says whether that is news: it was not failing, or failed for
    * another reason. The same reason again is not. */
  def isNew(clientId: ClientId, reason: String): UIO[Boolean] =
    state.modify(known => (!known.get(clientId).contains(reason), known.updated(clientId, reason)))

  /** The renewal of `clientId` succeeded: a later failure is news again. */
  def recovered(clientId: ClientId): UIO[Unit] = state.update(_ - clientId)

  /** Forgets every client that is not in `due`. */
  def retainOnly(due: Set[ClientId]): UIO[Unit] = state.update(_.view.filterKeys(due).toMap)

  def failing: UIO[Map[ClientId, String]] = state.get

object RenewalFailures:
  def make: UIO[RenewalFailures] = Ref.make(Map.empty[ClientId, String]).map(RenewalFailures(_))
