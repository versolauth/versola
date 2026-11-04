package versola.edge.login

import versola.util.UnitSpecBase
import zio.test.*

object BrowserBindingSpec extends UnitSpecBase:

  private val secret = "secret-1"

  def spec = suite("BrowserBinding")(
    suite("accepts")(
      test("accepts the secret whose hash was stored") {
        assertTrue(BrowserBinding.accepts(Some(BrowserBinding.hash(secret)), Some(secret)))
      },
      test("refuses another secret") {
        assertTrue(!BrowserBinding.accepts(Some(BrowserBinding.hash(secret)), Some("secret-2")))
      },
      test("refuses a request that sent no secret when the login has one") {
        assertTrue(!BrowserBinding.accepts(Some(BrowserBinding.hash(secret)), None))
      },
      test("refuses the stored hash presented as the secret, which is all a database read yields") {
        val stored = BrowserBinding.hash(secret)
        assertTrue(!BrowserBinding.accepts(Some(stored), Some(stored)))
      },
      test("refuses an empty secret") {
        assertTrue(!BrowserBinding.accepts(Some(BrowserBinding.hash(secret)), Some("")))
      },
      test("accepts any request for a login begun before the binding existed") {
        assertTrue(BrowserBinding.accepts(None, None), BrowserBinding.accepts(None, Some(secret)))
      },
    ),
    suite("hash")(
      test("is deterministic and does not reveal the secret") {
        assertTrue(
          BrowserBinding.hash(secret) == BrowserBinding.hash(secret),
          BrowserBinding.hash(secret) != BrowserBinding.hash("secret-2"),
          !BrowserBinding.hash(secret).contains(secret),
        )
      },
    ),
  )
