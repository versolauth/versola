package versola.auth.model

import versola.user.model.UserId
import versola.util.{Salt, Secret}

import java.time.Instant

case class PasswordRecord(
    id: Long,
    userId: UserId,
    password: Secret,
    salt: Salt,
    createdAt: Instant,
    isCurrent: Boolean,
)
