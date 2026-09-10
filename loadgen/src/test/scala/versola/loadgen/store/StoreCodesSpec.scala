package versola.loadgen.store

import versola.loadgen.model.*
import zio.test.*

/** The codes are a storage format: a campaign's rows outlive the driver that wrote them, so
  * these numbers are pinned here as well as in [[StoreCodes]]. A change that renumbers a case
  * has to fail a test rather than quietly reinterpret every row already in `vu_users`.
  */
object StoreCodesSpec extends ZIOSpecDefault:

  private def roundTrips[A](codes: StoreCodes.Codes[A], all: Array[A]): Boolean =
    all.forall(value => codes.decode(codes.encode(value)) == value)

  def spec = suite("StoreCodes")(
    test("every enum case round-trips through its SMALLINT code") {
      assertTrue(
        roundTrips(StoreCodes.activityClass, ActivityClass.values),
        roundTrips(StoreCodes.platform, Platform.values),
        roundTrips(StoreCodes.credential, CredentialKind.values),
        roundTrips(StoreCodes.role, UserRole.values),
        roundTrips(StoreCodes.userState, VirtualUserState.values),
        roundTrips(StoreCodes.sessionKind, SessionKind.values),
        roundTrips(StoreCodes.measurementKind, MeasurementKind.values),
      )
    },
    test("codes are the pinned values, not the declaration order of the enum") {
      assertTrue(
        StoreCodes.activityClass.encode(ActivityClass.Heavy) == 0.toShort,
        StoreCodes.activityClass.encode(ActivityClass.Regular) == 1.toShort,
        StoreCodes.activityClass.encode(ActivityClass.Light) == 2.toShort,
        StoreCodes.activityClass.encode(ActivityClass.Dormant) == 3.toShort,
        StoreCodes.credential.encode(CredentialKind.Otp) == 0.toShort,
        StoreCodes.credential.encode(CredentialKind.OtpPassword) == 1.toShort,
        StoreCodes.credential.encode(CredentialKind.Passkey) == 2.toShort,
        StoreCodes.userState.encode(VirtualUserState.Planned) == 0.toShort,
        StoreCodes.userState.encode(VirtualUserState.Registered) == 1.toShort,
        StoreCodes.userState.encode(VirtualUserState.Broken) == 2.toShort,
        StoreCodes.sessionKind.encode(SessionKind.MobileToken) == 0.toShort,
        StoreCodes.sessionKind.encode(SessionKind.WebCookie) == 1.toShort,
        StoreCodes.measurementKind.encode(MeasurementKind.Step) == 0.toShort,
        StoreCodes.measurementKind.encode(MeasurementKind.Flow) == 1.toShort,
      )
    },
    test("an unrecognised code is a defect on the read path, not a fallback value") {
      assertTrue(
        StoreCodes.userState.decodeOption(9) == None,
        scala.util.Try(StoreCodes.userState.decode(9)).isFailure,
      )
    },
  )
