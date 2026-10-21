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
