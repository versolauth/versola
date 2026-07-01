package versola.central.configuration.locales

import org.scalamock.stubs.ZIOStubs
import zio.*
import zio.test.*

object LocaleServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val enActive   = LocaleRecord("en", "English", isDefault = true,  active = true)
  private val ruActive   = LocaleRecord("ru", "Russian", isDefault = false, active = true)
  private val frInactive = LocaleRecord("fr", "French",  isDefault = false, active = false)

  class Env:
    val repository = stub[LocaleRepository]
    val service    = LocaleService.Impl(repository)

  def spec = suite("LocaleService")(
    test("getActive returns only active locales") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive, ruActive, frInactive))
        result <- env.service.getActive
      yield assertTrue(result == Vector(enActive, ruActive))
    },
    test("setDefault returns Right(()) for an existing active locale") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive, ruActive))
        _ <- env.repository.setDefault.succeedsWith(())
        result <- env.service.setDefault("ru")
      yield assertTrue(
        result == Right(()),
        env.repository.setDefault.calls == List("ru"),
      )
    },
    test("setDefault returns Left(Inactive) when locale is inactive") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive, frInactive))
        result <- env.service.setDefault("fr")
      yield assertTrue(
        result == Left(SetDefaultLocaleError.Inactive),
        env.repository.setDefault.calls.isEmpty,
      )
    },
    test("setDefault returns Left(NotFound) when locale does not exist") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive))
        result <- env.service.setDefault("de")
      yield assertTrue(
        result == Left(SetDefaultLocaleError.NotFound),
        env.repository.setDefault.calls.isEmpty,
      )
    },
  )
