package versola.util.http

import zio.*
import zio.test.*

object ReadinessServiceSpec extends ZIOSpecDefault:

  def spec = suite("ReadinessService")(
    test("starts out not ready") {
      for
        service <- ReadinessService.make
        ready <- service.isReady
      yield assertTrue(!ready)
    },
    test("becomes ready after setReady and stays ready") {
      for
        service <- ReadinessService.make
        _ <- service.setReady
        ready <- service.isReady
        _ <- service.setReady
        stillReady <- service.isReady
      yield assertTrue(ready, stillReady)
    },
  )
