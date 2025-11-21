package versola.util

import org.scalamock.stubs.ZIOStubs
import zio.ZLayer
import zio.test.{TestAspect, ZIOSpec}

abstract class UnitSpecBase extends ZIOSpec[Any], ZIOStubs:
  val bootstrap = ZLayer.empty
