package versola.edge.session

import versola.edge.model.{AccessTokenId, PresetId}
import zio.{Duration, Task}

trait EdgeRefreshTokenRepository:
  def create(
      accessTokenId: AccessTokenId,
      record: EdgeRefreshTokenRecord,
  ): Task[Unit]

  def find(accessTokenId: AccessTokenId): Task[Option[EdgeRefreshTokenRecord]]

  def delete(accessTokenId: AccessTokenId): Task[Unit]

  def deleteByPresetId(presetId: PresetId): Task[Unit]
