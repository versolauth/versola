package versola.role.model

import zio.json.ast.Json

import java.time.Instant

case class RoleRecord(
    id: RoleId,
    description: Json.Obj,
    active: Boolean,
)
