package versola.role.model

import zio.json.ast.Json

case class PermissionRecord(
    permission: Permission,
    description: Json.Obj,
)

