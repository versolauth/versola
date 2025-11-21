package versola.auth

import versola.auth.model.*
import versola.http.{ControllerSpec, NoopTracing}
import versola.user.model.*
import zio.ZLayer
import zio.http.*
import zio.json.ast.Json

import java.time.LocalDate
import java.util.UUID

object AuthControllerSpec extends ControllerSpec(AuthController):
  type Service = AuthService

  val email1 = Email("test@example.com")
  val authId1 = AuthId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
  val otpCode1 = OtpCode("123456")
  val accessToken1 = AccessToken("access_token_example")
  val refreshToken1 = RefreshToken("refresh_token_example")
  val deviceId1 = DeviceId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val issuedTokens1 = IssuedTokens(accessToken1, refreshToken1, None, None)
  val user = UserResponse(
    email = Some(Email("test@example.com")),
    firstName = Some(FirstName("John")),
    middleName = Some(MiddleName("Doe")),
    lastName = Some(LastName("Doe")),
    birthDate = Some(BirthDate(LocalDate.parse("1990-01-01"))),
    createdAt = java.time.Instant.now(),
  )
  val issuedTokensWithDevice = IssuedTokens(accessToken1, refreshToken1, Some(deviceId1), Some(user))

  override def spec =
    suite("AuthController")(
      suite("POST /auth/start")(
        testCase(
          description = "return authId for valid email address",
          request = Request.post(
            path = "/auth/start",
            body = Body.from(
              Json.Obj(
                "email" -> Json.Str(email1),
              ),
            ),
          ),
          setup = _.sendEmail.succeedsWith(authId1),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "authId" -> Json.Str(authId1.toString),
              ),
            ),
          ),
        ),
        testCase(
          description = "return authId for valid email address with deviceId",
          request = Request.post(
            path = "/auth/start",
            body = Body.from(
              Json.Obj(
                "email" -> Json.Str(email1),
                "deviceId" -> Json.Str(deviceId1.toString),
              ),
            ),
          ),
          setup = _.sendEmail.succeedsWith(authId1),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "authId" -> Json.Str(authId1.toString),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle invalid email address",
          request = Request.post(
            path = "/auth/start",
            body = Body.from(
              Json.Obj(
                "email" -> Json.Str("invalid_email"),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .email(invalid_email is invalid email)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle invalid deviceId format",
          request = Request.post(
            path = "/auth/start",
            body = Body.from(
              Json.Obj(
                "email" -> Json.Str(email1),
                "deviceId" -> Json.Str("invalid-device-id"),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .deviceId(UUID has to be represented by the standard 36-char representation)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle missing phone field",
          request = Request.post(
            path = "/auth/start",
            body = Body.from(
              Json.Obj(
                "deviceId" -> Json.Str(deviceId1.toString),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .email(missing)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle service failure",
          request = Request.post(
            path = "/auth/start",
            body = Body.from(
              Json.Obj(
                "email" -> Json.Str(email1),
              ),
            ),
          ),
          setup = _.sendEmail.failsWith(RuntimeException("Email service unavailable")),
          expectedResponse = Response(
            status = Status.InternalServerError,
            body = Body.empty,
          ),
        ),
      ),
      suite("POST /auth/otp")(
        testCase(
          description = "return tokens for valid OTP",
          request = Request.post(
            path = "/auth/otp",
            body = Body.from(
              Json.Obj(
                "code" -> Json.Str(otpCode1),
                "authId" -> Json.Str(authId1.toString),
              ),
            ),
          ),
          setup = _.verifyEmail.succeedsWith(issuedTokens1.copy(user = Some(user))),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "accessToken" -> Json.Str(issuedTokens1.accessToken),
                "refreshToken" -> Json.Str(issuedTokens1.refreshToken),
                "deviceId" -> Json.Null,
                "user" -> Json.Obj(
                  "email" -> Json.Str(email1),
                  "firstName" -> Json.Str("John"),
                  "middleName" -> Json.Str("Doe"),
                  "lastName" -> Json.Str("Doe"),
                  "birthDate" -> Json.Str("1990-01-01"),
                  "createdAt" -> Json.Str(user.createdAt.toString),
                ),
              ),
            ),
          ),
        ),
        testCase(
          description = "return UnprocessableEntity for invalid OTP",
          request = Request.post(
            path = "/auth/otp",
            body = Body.from(
              Json.Obj(
                "code" -> Json.Str(otpCode1),
                "authId" -> Json.Str(authId1.toString),
              ),
            ),
          ),
          setup = _.verifyEmail.failsWith(AttemptsLeft(1)),
          expectedResponse = Response(
            status = Status.UnprocessableEntity,
            body = Body.from(
              Json.Obj(
                "attemptsLeft" -> Json.Num(1),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle invalid OTP code format",
          request = Request.post(
            path = "/auth/otp",
            body = Body.from(
              Json.Obj(
                "code" -> Json.Str("12345"), // Too short
                "authId" -> Json.Str("12345678901234567890"),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .code(Invalid OTP code)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle invalid authId format",
          request = Request.post(
            path = "/auth/otp",
            body = Body.from(
              Json.Obj(
                "code" -> Json.Str("123456"),
                "authId" -> Json.Str("short"), // Too short
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .authId(UUID has to be represented by the standard 36-char representation)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle missing code field",
          request = Request.post(
            path = "/auth/otp",
            body = Body.from(
              Json.Obj(
                "authId" -> Json.Str(authId1.toString),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .code(missing)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle missing authId field",
          request = Request.post(
            path = "/auth/otp",
            body = Body.from(
              Json.Obj(
                "code" -> Json.Str(otpCode1),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .authId(missing)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle service failure",
          request = Request.post(
            path = "/auth/otp",
            body = Body.from(
              Json.Obj(
                "code" -> Json.Str(otpCode1),
                "authId" -> Json.Str(authId1.toString),
              ),
            ),
          ),
          setup = _.verifyEmail.failsWith(RuntimeException("Database connection failed")),
          expectedResponse = Response(
            status = Status.InternalServerError,
            body = Body.empty,
          ),
        ),
      ),
      suite("GET /auth/jwks")(
        testCase(
          description = "return JWKS JSON",
          request = Request.get(
            path = "/auth/jwks",
          ),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(TestEnvConfig.jwksJson),
          ),
        ),
      ),
      suite("POST /auth/refresh")(
        testCase(
          description = "return new tokens for valid refresh token",
          request = Request.post(
            path = "/auth/refresh",
            body = Body.from(
              Json.Obj(
                "refreshToken" -> Json.Str(refreshToken1),
                "deviceId" -> Json.Str(deviceId1.toString),
              ),
            ),
          ),
          setup = _.refreshTokens.succeedsWith(issuedTokens1),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "accessToken" -> Json.Str(issuedTokens1.accessToken),
                "refreshToken" -> Json.Str(issuedTokens1.refreshToken),
                "deviceId" -> Json.Null,
                "user" -> Json.Null,
              ),
            ),
          ),
        ),
        testCase(
          description = "return Unauthorized for invalid refresh token",
          request = Request.post(
            path = "/auth/refresh",
            body = Body.from(
              Json.Obj(
                "refreshToken" -> Json.Str(refreshToken1),
                "deviceId" -> Json.Str(deviceId1.toString),
              ),
            ),
          ),
          setup = _.refreshTokens.failsWith(()),
          expectedResponse = Response(
            status = Status.Unauthorized,
            body = Body.empty,
          ),
        ),
        testCase(
          description = "handle invalid deviceId format",
          request = Request.post(
            path = "/auth/refresh",
            body = Body.from(
              Json.Obj(
                "refreshToken" -> Json.Str(refreshToken1),
                "deviceId" -> Json.Str("invalid-uuid"),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .deviceId(UUID has to be represented by the standard 36-char representation)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle missing refreshToken field",
          request = Request.post(
            path = "/auth/refresh",
            body = Body.from(
              Json.Obj(
                "deviceId" -> Json.Str(deviceId1.toString),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .refreshToken(missing)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle missing deviceId field",
          request = Request.post(
            path = "/auth/refresh",
            body = Body.from(
              Json.Obj(
                "refreshToken" -> Json.Str(refreshToken1),
              ),
            ),
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: .deviceId(missing)"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle empty request body",
          request = Request.post(
            path = "/auth/refresh",
            body = Body.empty,
          ),
          expectedResponse = Response(
            status = Status.BadRequest,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("MalformedBody"),
                "message" -> Json.Str("Malformed request body failed to decode: Unexpected end of input"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle service failure",
          request = Request.post(
            path = "/auth/refresh",
            body = Body.from(
              Json.Obj(
                "refreshToken" -> Json.Str(refreshToken1),
                "deviceId" -> Json.Str(deviceId1.toString),
              ),
            ),
          ),
          setup = _.refreshTokens.failsWith(RuntimeException("Token service unavailable")),
          expectedResponse = Response(
            status = Status.InternalServerError,
            body = Body.empty,
          ),
        ),
      ),
      suite("POST /auth/logout")(
        testCase(
          description = "successfully logout with valid JWT token",
          request = Request.post(
            path = "/auth/logout",
            body = Body.empty,
          ).addHeader(Header.Authorization.Bearer(TestEnvConfig.createTestAccessToken(userId1, authId1, deviceId1))),
          setup = _.logout.succeedsWith(()),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.empty,
          ),
        ),
        testCase(
          description = "return Unauthorized when no JWT token provided",
          request = Request.post(
            path = "/auth/logout",
            body = Body.empty,
          ),
          expectedResponse = Response(
            status = Status.Unauthorized,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("Unauthorized"),
                "message" -> Json.Str("token not provided"),
              ),
            ),
          ),
        ),
        testCase(
          description = "return Unauthorized when invalid JWT token provided",
          request = Request.post(
            path = "/auth/logout",
            body = Body.empty,
          ).addHeader(Header.Authorization.Bearer(AccessToken("invalid.jwt.token"))),
          expectedResponse = Response(
            status = Status.Unauthorized,
            body = Body.from(
              Json.Obj(
                "name" -> Json.Str("Unauthorized"),
                "message" -> Json.Str("invalid token format"),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle service failure during logout",
          request = Request.post(
            path = "/auth/logout",
            body = Body.empty,
          ).addHeader(Header.Authorization.Bearer(TestEnvConfig.createTestAccessToken(userId1, authId1, deviceId1))),
          setup = _.logout.failsWith(RuntimeException("Database connection failed")),
          expectedResponse = Response(
            status = Status.InternalServerError,
            body = Body.empty,
          ),
        ),
      ),
    ).provide(
      ZLayer.succeed(stub[AuthService]),
      NoopTracing.layer,
      ZLayer.succeed(TestEnvConfig.jwtConfig.jwkSet),
    )
