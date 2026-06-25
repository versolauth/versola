package versola.oauth.client.model

import versola.util.UnitSpecBase
import zio.Chunk
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

object AuthMethodRefSpec extends UnitSpecBase:

  def spec = suite("AuthMethodRef")(
    suite("amrClaim")(
      test("returns flattened methods without mfa for a single factor") {
        val amr = Map(
          PassedAuthFactor.otp -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.otp, AuthMethodRef.sms)),
        )
        assertTrue(AuthMethodRef.amrClaim(amr) == Set(AuthMethodRef.otp, AuthMethodRef.sms))
      },
      test("adds mfa when two or more distinct factors were passed") {
        val amr = Map(
          PassedAuthFactor.password -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.pwd)),
          PassedAuthFactor.otp -> PassedFactorRecord(Instant.EPOCH, Set(AuthMethodRef.otp, AuthMethodRef.sms)),
        )
        assertTrue(
          AuthMethodRef.amrClaim(amr) ==
            Set(AuthMethodRef.pwd, AuthMethodRef.otp, AuthMethodRef.sms, AuthMethodRef.mfa),
        )
      },
      test("returns empty set for empty input") {
        assertTrue(AuthMethodRef.amrClaim(Map.empty) == Set.empty[AuthMethodRef])
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
