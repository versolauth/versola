package versola.oauth.client.model

import versola.util.UnitSpecBase
import zio.Chunk
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object AuthMethodRefSpec extends UnitSpecBase:

  def spec = suite("AuthMethodRef")(
    suite("amrClaim")(
      test("returns empty set for empty input") {
        assertTrue(AuthMethodRef.amrClaim(Map.empty) == Set.empty[AuthMethodRef])
      },
      test("otp factor expands to {otp, sms} without mfa") {
        val amr = Map(
          PassedAuthFactor.otp -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.otp, AuthMethodRef.sms)),
        )
        assertTrue(AuthMethodRef.amrClaim(amr) == Set(AuthMethodRef.otp, AuthMethodRef.sms))
      },
      test("passkey factor expands to {swk, user} without mfa") {
        val amr = Map(
          PassedAuthFactor.passkey -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.swk, AuthMethodRef.user)),
        )
        assertTrue(AuthMethodRef.amrClaim(amr) == Set(AuthMethodRef.swk, AuthMethodRef.user))
      },
      test("passkey factor with hwk expands to {hwk, user} without mfa") {
        val amr = Map(
          PassedAuthFactor.passkey -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.hwk, AuthMethodRef.user)),
        )
        assertTrue(AuthMethodRef.amrClaim(amr) == Set(AuthMethodRef.hwk, AuthMethodRef.user))
      },
      test("password + otp adds mfa") {
        val amr = Map(
          PassedAuthFactor.password -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.pwd)),
          PassedAuthFactor.otp      -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.otp, AuthMethodRef.sms)),
        )
        assertTrue(
          AuthMethodRef.amrClaim(amr) ==
            Set(AuthMethodRef.pwd, AuthMethodRef.otp, AuthMethodRef.sms, AuthMethodRef.mfa),
        )
      },
      test("password + passkey adds mfa") {
        val amr = Map(
          PassedAuthFactor.password -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.pwd)),
          PassedAuthFactor.passkey  -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.swk, AuthMethodRef.user)),
        )
        assertTrue(
          AuthMethodRef.amrClaim(amr) ==
            Set(AuthMethodRef.pwd, AuthMethodRef.swk, AuthMethodRef.user, AuthMethodRef.mfa),
        )
      },
    ),
    suite("idTokenClaims")(
      test("emits sorted amr array and auth_time as epoch seconds") {
        val claims = AuthMethodRef.idTokenClaims(
          Set(AuthMethodRef.pwd, AuthMethodRef.otp, AuthMethodRef.mfa),
          Some(Instant.ofEpochSecond(1700000000)),
        )
        assertTrue(
          claims("amr") == Json.Arr(Chunk(Json.Str("mfa"), Json.Str("otp"), Json.Str("pwd"))),
          claims("auth_time") == Json.Num(1700000000L),
        )
      },
      test("omits amr when no methods are present") {
        val claims = AuthMethodRef.idTokenClaims(Set.empty, Some(Instant.ofEpochSecond(42)))
        assertTrue(
          !claims.contains("amr"),
          claims("auth_time") == Json.Num(42L),
        )
      },
      test("omits auth_time when not provided") {
        val claims = AuthMethodRef.idTokenClaims(Set(AuthMethodRef.pwd), None)
        assertTrue(
          claims("amr") == Json.Arr(Chunk(Json.Str("pwd"))),
          !claims.contains("auth_time"),
        )
      },
    ),
    suite("toPassedAuthFactor")(
      test("prefers passkey when a key-bound method is present") {
        assertTrue(
          AuthMethodRef.toPassedAuthFactor(Set(AuthMethodRef.swk, AuthMethodRef.pwd)) == Some(PassedAuthFactor.passkey),
          AuthMethodRef.toPassedAuthFactor(Set(AuthMethodRef.hwk)) == Some(PassedAuthFactor.passkey),
        )
      },
      test("maps pwd to password and otp to otp") {
        assertTrue(
          AuthMethodRef.toPassedAuthFactor(Set(AuthMethodRef.pwd)) == Some(PassedAuthFactor.password),
          AuthMethodRef.toPassedAuthFactor(Set(AuthMethodRef.otp, AuthMethodRef.sms)) == Some(PassedAuthFactor.otp),
        )
      },
      test("returns None when no recognizable method is present") {
        assertTrue(AuthMethodRef.toPassedAuthFactor(Set(AuthMethodRef.user)) == None)
      },
    ),
  )
