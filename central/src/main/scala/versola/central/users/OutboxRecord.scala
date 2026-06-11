package versola.central.users

import java.util.UUID

case class OutboxRecord(
    id: UUID,
    userId: UserId,
    event: OutboxEvent,
    attempts: Int,
)
