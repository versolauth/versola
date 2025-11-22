package versola.util

import versola.security.SecureRandom
import zio.ZIO
import zio.test.*

object SecureRandomSpec extends UnitSpecBase:

  override def spec: Spec[Any, Any] =
    suite("SecureRandom")(
      nextBytesTests,
      nextHexTests,
      nextNumericTests,
      nextAlphanumericTests,
      executeTests,
    ).provide(SecureRandom.live)

  private def nextBytesTests =
    suite("nextBytes")(
      test("generate byte array of specified length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          bytes <- secureRandom.nextBytes(10)
        } yield assertTrue(
          bytes.length == 10
        )
      },
      test("generate empty array for zero length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          bytes <- secureRandom.nextBytes(0)
        } yield assertTrue(
          bytes.length == 0
        )
      },
      test("generate different arrays on subsequent calls") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          bytes1 <- secureRandom.nextBytes(16)
          bytes2 <- secureRandom.nextBytes(16)
          equal = java.util.Arrays.equals(bytes1, bytes2)
        } yield assertTrue(
          bytes1.length == 16,
          bytes2.length == 16,
          !equal
        )
      },
      test("generate large byte arrays") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          bytes <- secureRandom.nextBytes(1024)
        } yield assertTrue(
          bytes.length == 1024
        )
      }
    )

  private def nextHexTests =
    suite("nextHex")(
      test("generate hex string of specified length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          hex <- secureRandom.nextHex(8)
        } yield assertTrue(
          hex.length == 8,
          hex.forall(c => "0123456789abcdef".contains(c))
        )
      },
      test("generate empty string for zero length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          hex <- secureRandom.nextHex(0)
        } yield assertTrue(
          hex.isEmpty
        )
      },
      test("generate different hex strings on subsequent calls") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          hex1 <- secureRandom.nextHex(16)
          hex2 <- secureRandom.nextHex(16)
        } yield assertTrue(
          hex1.length == 16,
          hex2.length == 16,
          hex1 != hex2, // Should be different (extremely high probability)
          hex1.forall(c => "0123456789abcdef".contains(c)),
          hex2.forall(c => "0123456789abcdef".contains(c))
        )
      },
      test("generate only valid hex characters") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          hex <- secureRandom.nextHex(100)
        } yield assertTrue(
          hex.length == 100,
          hex.forall(c => "0123456789abcdef".contains(c))
        )
      }
    )

  private def nextNumericTests =
    suite("nextNumeric")(
      test("generate numeric string of specified length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          numeric <- secureRandom.nextNumeric(6)
        } yield assertTrue(
          numeric.length == 6,
          numeric.forall(c => "0123456789".contains(c))
        )
      },
      test("generate empty string for zero length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          numeric <- secureRandom.nextNumeric(0)
        } yield assertTrue(
          numeric.isEmpty
        )
      },
      test("generate different numeric strings on subsequent calls") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          numeric1 <- secureRandom.nextNumeric(10)
          numeric2 <- secureRandom.nextNumeric(10)
        } yield assertTrue(
          numeric1.length == 10,
          numeric2.length == 10,
          numeric1 != numeric2, // Should be different (extremely high probability)
          numeric1.forall(c => "0123456789".contains(c)),
          numeric2.forall(c => "0123456789".contains(c))
        )
      },
      test("generate only valid numeric characters") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          numeric <- secureRandom.nextNumeric(50)
        } yield assertTrue(
          numeric.length == 50,
          numeric.forall(c => "0123456789".contains(c))
        )
      }
    )

  private def nextAlphanumericTests =
    suite("nextAlphanumeric")(
      test("generate alphanumeric string of specified length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          alphanumeric <- secureRandom.nextAlphanumeric(12)
        } yield assertTrue(
          alphanumeric.length == 12,
          alphanumeric.forall(c => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".contains(c))
        )
      },
      test("generate empty string for zero length") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          alphanumeric <- secureRandom.nextAlphanumeric(0)
        } yield assertTrue(
          alphanumeric.isEmpty
        )
      },
      test("generate different alphanumeric strings on subsequent calls") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          alphanumeric1 <- secureRandom.nextAlphanumeric(20)
          alphanumeric2 <- secureRandom.nextAlphanumeric(20)
        } yield assertTrue(
          alphanumeric1.length == 20,
          alphanumeric2.length == 20,
          alphanumeric1 != alphanumeric2, // Should be different (extremely high probability)
          alphanumeric1.forall(c => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".contains(c)),
          alphanumeric2.forall(c => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".contains(c))
        )
      },
      test("generate only valid alphanumeric characters") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          alphanumeric <- secureRandom.nextAlphanumeric(100)
        } yield assertTrue(
          alphanumeric.length == 100,
          alphanumeric.forall(c => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".contains(c))
        )
      }
    )

  private def executeTests =
    suite("execute")(
      test("execute function with underlying SecureRandom") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          result <- secureRandom.execute(_.nextInt(100))
        } yield assertTrue(
          result >= 0,
          result < 100
        )
      },
      test("execute multiple operations") {
        for {
          secureRandom <- ZIO.service[SecureRandom]
          result1 <- secureRandom.execute(_.nextInt(1000))
          result2 <- secureRandom.execute(_.nextBoolean())
          result3 <- secureRandom.execute(_.nextDouble())
        } yield assertTrue(
          result1 >= 0 && result1 < 1000,
          result3 >= 0.0 && result3 < 1.0
        )
      }
    )


