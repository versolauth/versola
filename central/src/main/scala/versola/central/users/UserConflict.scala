package versola.central.users

import versola.central.configuration.topology.CellId

type UserConflict = UserConflict.type

case object UserConflict

/** The user to patch does not exist in the index. */
type UserNotFound = UserNotFound.type

case object UserNotFound

/** A self-service registration claim resolved to more than one existing user index row
  * (e.g. the supplied email and phone are already indexed under different users).
  */
type UserIndexConflict = UserIndexConflict.type

case object UserIndexConflict

/** The user has a move between cells that is not finished (versolauth/versola#1051): a change of the user waits for it, so that it
  * is not made to the cell the user is leaving and lost. Answered `409` with the error code `user_moving`. */
type UserMoving = UserMoving.type

case object UserMoving

/** A cell that does not take users (closed, draining, or of weight 0) was asked to register an address nobody holds. Answered
  * `409` with the error code `cell_not_taking` and the cell that does take it, which is the one [[UserService.placementCell]]
  * picks for the address among the cells that take users.
  */
final case class CellNotTaking(cell: CellId)

/** The same refusal when no cell takes users: answered `409` with the error code `no_cell_takes_users`. */
type NoCellTakesUsers = NoCellTakesUsers.type

case object NoCellTakesUsers
