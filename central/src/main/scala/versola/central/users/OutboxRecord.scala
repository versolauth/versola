package versola.central.users

import java.util.UUID

case class OutboxRecord(
    id: UUID,
    event: OutboxEvent,
    attempts: Int,
)
