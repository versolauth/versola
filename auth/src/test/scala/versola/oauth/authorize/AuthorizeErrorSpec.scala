package versola.oauth.authorize

import versola.oauth.authorize.model.Error
import versola.oauth.model.State
import zio.http.URL
import zio.test.*

object AuthorizeErrorSpec extends ZIOSpecDefault:

  private val redirectUri = URL.decode("https://example.com/callback").toOption.get

  def spec = suite("AuthorizeError")(
    test("ResponseTypeMissing includes error and error_description query params") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None)
      val url = err.redirectUriWithErrorParams
      val params = url.queryParams
      assertTrue(
        params.map.get("error").exists(_.contains("invalid_request")),
        params.map.get("error_description").exists(_.nonEmpty),
      )
    },

    test("ResponseTypeMissing includes error_uri when defined") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None)
      val url = err.redirectUriWithErrorParams
      val params = url.queryParams
      assertTrue(params.map.get("error_uri").exists(_.nonEmpty))
    },

    test("state is included when provided") {
      val err = Error.ResponseTypeMissing(redirectUri, state = Some(State("abc123")))
      val url = err.redirectUriWithErrorParams
      val params = url.queryParams
      assertTrue(params.map.get("state").exists(_.contains("abc123")))
    },

    test("state is absent when not provided") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None)
      val url = err.redirectUriWithErrorParams
      val params = url.queryParams
      assertTrue(params.map.get("state").isEmpty)
    },

    test("UnsupportedResponseType uses correct error code") {
      val err = Error.UnsupportedResponseType(redirectUri, state = None, responseType = "token")
      val url = err.redirectUriWithErrorParams
      assertTrue(url.queryParams.map.get("error").exists(_.contains("unsupported_response_type")))
    },

    test("AuthFlowMissing has no error_uri") {
      val err = Error.AuthFlowMissing(redirectUri, state = None)
      val url = err.redirectUriWithErrorParams
      assertTrue(url.queryParams.map.get("error_uri").isEmpty)
    },

    test("AccessDenied uses access_denied error code") {
      val err = Error.AccessDenied(redirectUri, state = None)
      val url = err.redirectUriWithErrorParams
      assertTrue(url.queryParams.map.get("error").exists(_.contains("access_denied")))
    },

    test("base uri is preserved in error redirect") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None)
      val url = err.redirectUriWithErrorParams
      assertTrue(url.host.contains("example.com"))
    },
  )
