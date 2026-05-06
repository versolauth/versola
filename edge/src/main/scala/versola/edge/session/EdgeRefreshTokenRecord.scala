package versola.edge.session

import versola.edge.model.{PresetId, RefreshToken}
import versola.util.Secret

import java.time.Instant

case class EdgeRefreshTokenRecord(
    presetId: PresetId,
    encryptedRefreshToken: Secret,
    expiresAt: Instant,
)
