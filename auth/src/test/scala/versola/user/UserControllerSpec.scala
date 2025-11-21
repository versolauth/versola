package versola.user

import versola.auth.TestEnvConfig
import versola.http.{ControllerSpec, NoopTracing}
import versola.user.model.{FieldName, PatchUserErrorResponse, PatchUserRequest, UserId}
import zio.ZLayer
import zio.http.*
import zio.json.ast.Json
import zio.test.assertTrue

import java.util.UUID

object UserControllerSpec extends ControllerSpec(UserController):
  type Service = UserService
  type Env = UserController.Env

  val userId1 = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  val accessToken = TestEnvConfig.createTestAccessToken()

  override def spec = {
    suite("UserController")(
      suite("PATCH /api/v1/user")(
        testCase(
          description = "return empty response for valid empty request",
          request = Request.patch(
            path = "/api/v1/user",
            body = Body.from(
              Json.Obj(
                "delete" -> Json.Arr(),
                "update" -> Json.Obj(),
              ),
            ),
          ).addHeader(Header.Authorization.Bearer(accessToken)),
          setup = _.updateProfile.succeedsWith(PatchUserErrorResponse.empty),
          verify = stub =>
            assertTrue(
              stub.updateProfile.calls.exists(_._2 == PatchUserRequest.empty),
            ),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "errors" -> Json.Obj(
                  "firstName" -> Json.Null,
                  "middleName" -> Json.Null,
                  "lastName" -> Json.Null,
                  "birthDate" -> Json.Null,
                ),
              ),
            ),
          ),
        ),
        testCase(
          description = "return validation errors for invalid request",
          request = Request.patch(
            path = "/api/v1/user",
            body = Body.from(
              Json.Obj(
                "delete" -> Json.Arr(),
                "update" -> Json.Obj(
                  "firstName" -> Json.Str("A"),
                ),
              ),
            ),
          ).addHeader(Header.Authorization.Bearer(accessToken)),
          setup = _.updateProfile.succeedsWith(
            PatchUserErrorResponse(PatchUserRequest.UpdateFields.empty.copy(firstName = Some("A is invalid name part"))),
          ),
          verify = stub =>
            assertTrue(
              stub.updateProfile.calls.exists(_._2 == PatchUserRequest.empty.copy(update = PatchUserRequest.UpdateFields.empty.copy(firstName = Some("A")))),
            ),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "errors" -> Json.Obj(
                  "firstName" -> Json.Str("A is invalid name part"),
                  "middleName" -> Json.Null,
                  "lastName" -> Json.Null,
                  "birthDate" -> Json.Null,
                ),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle update request successfully",
          request = Request.patch(
            path = "/api/v1/user",
            body = Body.from(
              Json.Obj(
                "delete" -> Json.Arr(),
                "update" -> Json.Obj(
                  "firstName" -> Json.Str("John"),
                ),
              ),
            ),
          ).addHeader(Header.Authorization.Bearer(accessToken)),
          setup = _.updateProfile.succeedsWith(PatchUserErrorResponse.empty),
          verify = stub =>
            assertTrue(
              stub.updateProfile.calls.exists(_._2 == PatchUserRequest.empty.copy(
                update = PatchUserRequest.UpdateFields.empty.copy(firstName = Some("John"))
              )),
            ),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "errors" -> Json.Obj(
                  "firstName" -> Json.Null,
                  "middleName" -> Json.Null,
                  "lastName" -> Json.Null,
                  "birthDate" -> Json.Null,
                ),
              ),
            ),
          ),
        ),
        testCase(
          description = "handle delete request successfully",
          request = Request.patch(
            path = "/api/v1/user",
            body = Body.from(
              Json.Obj(
                "delete" -> Json.Arr(
                  Json.Str("firstName"),
                ),
                "update" -> Json.Obj(),
              ),
            ),
          ).addHeader(Header.Authorization.Bearer(accessToken)),
          setup = _.updateProfile.succeedsWith(PatchUserErrorResponse.empty),
          verify = stub =>
            assertTrue(
              stub.updateProfile.calls.exists(_._2 == PatchUserRequest(Set(FieldName.firstName), PatchUserRequest.UpdateFields.empty)),
            ),
          expectedResponse = Response(
            status = Status.Ok,
            body = Body.from(
              Json.Obj(
                "errors" -> Json.Obj(
                  "firstName" -> Json.Null,
                  "middleName" -> Json.Null,
                  "lastName" -> Json.Null,
                  "birthDate" -> Json.Null,
                ),
              ),
            ),
          ),
        ),
      ),
    ).provide(
      ZLayer.succeed(stub[UserService]),
      NoopTracing.layer,
      ZLayer.succeed(TestEnvConfig.jwtConfig.jwkSet),
    )
  }
