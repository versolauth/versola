package versola.central.configuration.sync

import zio.*
import zio.test.*

object CacheSyncRepositorySpec extends ZIOSpecDefault:
  def spec = suite("CacheSyncRepository")(
    test("noop never emits a notification") {
      for
        repository <- ZIO.service[CacheSyncRepository]
        notifications <- repository.getNotifications.runCollect
      yield assertTrue(notifications.isEmpty)
    },
  ).provide(CacheSyncRepository.noop)
