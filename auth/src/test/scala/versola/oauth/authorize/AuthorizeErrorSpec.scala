package versola.oauth.authorize

import versola.oauth.authorize.model.Error
import versola.oauth.model.State
import zio.http.URL
import zio.test.*

object AuthorizeErrorSpec extends ZIOSpecDefault:

  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val testIss = "https://auth.example.com"

  def spec = suite("AuthorizeError")(
    test("ResponseTypeMissing includes error and error_description query params") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None, useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      val params = url.queryParams
      assertTrue(
        params.map.get("error").exists(_.contains("invalid_request")),
        params.map.get("error_description").exists(_.nonEmpty),
      )
    },

    test("ResponseTypeMissing includes error_uri when defined") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None, useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      val params = url.queryParams
      assertTrue(params.map.get("error_uri").exists(_.nonEmpty))
    },

    test("state is included when provided") {
      val err = Error.ResponseTypeMissing(redirectUri, state = Some(State("abc123")), useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      val params = url.queryParams
      assertTrue(params.map.get("state").exists(_.contains("abc123")))
    },

    test("state is absent when not provided") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None, useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      val params = url.queryParams
      assertTrue(params.map.get("state").isEmpty)
    },

    test("UnsupportedResponseType uses correct error code") {
      val err = Error.UnsupportedResponseType(redirectUri, state = None, responseType = "token", useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      assertTrue(url.queryParams.map.get("error").exists(_.contains("unsupported_response_type")))
    },

    test("AuthFlowMissing has no error_uri") {
      val err = Error.AuthFlowMissing(redirectUri, state = None, useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      assertTrue(url.queryParams.map.get("error_uri").isEmpty)
    },

    test("AccessDenied uses access_denied error code") {
      val err = Error.AccessDenied(redirectUri, state = None, useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      assertTrue(url.queryParams.map.get("error").exists(_.contains("access_denied")))
    },

    test("ScopeNotGranted uses invalid_scope error code and names the offending scopes") {
      val err = Error.ScopeNotGranted(redirectUri, state = None, value = "address phone", useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      assertTrue(
        url.queryParams.map.get("error").exists(_.contains("invalid_scope")),
        url.queryParams.map.get("error_description").exists(_.exists(_.contains("address phone"))),
      )
    },

    test("base uri is preserved in error redirect") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None, useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      assertTrue(url.host.contains("example.com"))
    },

    test("iss is included in error redirect") {
      val err = Error.ResponseTypeMissing(redirectUri, state = None, useFragment = false)
      val url = err.redirectUriWithErrorParams(testIss)
      assertTrue(url.queryParams.map.get("iss").exists(_.contains(testIss)))
    },

    suite("fragment response mode (useFragment = true)")(
      test("params go to URL fragment, not query string") {
        val err = Error.LoginRequired(redirectUri, state = None, useFragment = true)
        val url = err.redirectUriWithErrorParams(testIss)
        assertTrue(
          url.queryParams.map.isEmpty,
          url.fragment.exists(_.contains("error=login_required")),
          url.fragment.exists(_.contains("iss=")),
        )
      },

      test("state is included in fragment when provided") {
        val err = Error.LoginRequired(redirectUri, state = Some(State("mystate")), useFragment = true)
        val url = err.redirectUriWithErrorParams(testIss)
        assertTrue(url.fragment.exists(_.contains("state=mystate")))
      },

      test("state is absent from fragment when not provided") {
        val err = Error.LoginRequired(redirectUri, state = None, useFragment = true)
        val url = err.redirectUriWithErrorParams(testIss)
        assertTrue(!url.fragment.exists(_.contains("state=")))
      },

      test("error_description is percent-encoded in fragment") {
        val err = Error.LoginRequired(redirectUri, state = None, useFragment = true)
        val url = err.redirectUriWithErrorParams(testIss)
        // spaces must not appear raw in the fragment — must be %20 or +
        assertTrue(url.fragment.forall(!_.contains(" ")))
      },
    ),
  )
