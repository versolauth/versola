package versola.util.revocation

import zio.test.*

object PostgresRevocationNotificationsSpec extends ZIOSpecDefault:

  def spec = suite("PostgresRevocationNotifications.parseNotification")(
    test("a payload this version cannot read is dropped rather than failing the stream") {
      assertTrue(
        PostgresRevocationNotifications.parseNotification("not json").isEmpty,
        PostgresRevocationNotifications.parseNotification("""{"key":"zzz:1","exp":1,"before":null}""").isEmpty,
      )
    },
  )
