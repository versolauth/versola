package versola.central.users

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
