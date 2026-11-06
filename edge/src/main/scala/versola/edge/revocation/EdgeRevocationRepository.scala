package versola.edge.revocation

import versola.util.revocation.{Revocation, RevocationRepository}
import zio.Task

/** The durable copy of the revocation list in edge's database: what [[RevocationRepository]]
  * reads, plus how this service writes it.
  */
trait EdgeRevocationRepository extends RevocationRepository:
  /** Records revocations, widening any key already recorded rather than skipping it: a key
    * can be revoked twice, and the second one covers tokens the first did not — a repeated
    * administrative logout moves `issuedBefore` forward over the tokens issued in between.
    * Neither field is ever narrowed, so the two writes can arrive in either order.
    *
    * Every replica, including the one writing, learns about them through
    * [[versola.util.revocation.RevocationNotifications]] rather than from the return value.
    */
  def revokeAll(revocations: List[Revocation]): Task[Unit]
