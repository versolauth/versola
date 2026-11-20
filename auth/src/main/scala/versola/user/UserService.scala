package versola.user

import versola.audit.AuditSupport.*
import versola.audit.AuthAuditDetails
import versola.audit.AuthAuditKinds
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.UserRecord
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.Observability
import versola.util.{CoreConfig, Email, Phone, RoutingKey}
import zio.*

trait UserService:
  /**
    * Resolve the credential to an account for registration.
    *
    * Central is the sole allocator of user IDs: it claims the credential and returns the
    * canonical ID, minting one on first claim. The configured tenant roles are assigned to the
    * resolved account regardless of whether it was just created; reaching this operation already
    * means the registration flow matched.
    */
  def register(
      credential: Either[Email, Phone],
      tenantId: TenantId,
      roleIds: Set[RoleId],
  ): Task[UserRecord]

object UserService:
  /** The credential is held by a user of another cell, which this cell cannot create or sign in. */
  final case class HomeCellElsewhere(cell: RoutingKey.CellId) extends RuntimeException(s"the user is held by cell '$cell'")

  def live: ZLayer[UserRepository & UserDirectory & CoreConfig & AuditEvents, Nothing, UserService] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      userRepository: UserRepository,
      directory: UserDirectory,
      config: CoreConfig,
      audit: AuditEvents,
  ) extends UserService:
    override def register(
        credential: Either[Email, Phone],
        tenantId: TenantId,
        roleIds: Set[RoleId],
    ): Task[UserRecord] =
      for
        claim <- directory.claim(credential)
        // The credential belongs to a user another cell holds: creating one here would be a second account under the
        // same id. Handing the sign-in to that cell is versolauth/versola#547 Phase 2 step 5; until then it is refused.
        _ <- ZIO.foreachDiscard(claim.cell.filterNot(config.cellId.contains)): home =>
          Observability.setError("user_home_cell_elsewhere", Some(s"the credential belongs to a user of cell '$home'")) *>
            ZIO.fail(UserService.HomeCellElsewhere(home))
        user <- userRepository.register(claim.userId, credential, tenantId, roleIds)
        _ <- audit.log(
          AuditDraft(
            kind = AuthAuditKinds.AccountRegistered,
            outcome = AuditOutcome.success,
            reason = None,
            actor = None,
            userId = Some(user.id),
            target = None,
            details = AuthAuditDetails.Account(via = "registration", detail = None),
          ),
        )
      yield user
