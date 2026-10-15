package versola.central.configuration.forms

import versola.central.configuration.system.SystemSettingsRecord
import zio.Chunk
import zio.json.JsonCodec
import zio.json.ast.Json

/** One state of a form the console can preview: the `step` object `auth` would inject into the page, and the
  * `error` key it would show above it.
  */
case class PreviewScenario(
    id: String,
    title: Map[String, String],
    step: Json,
    error: Option[String] = None,
) derives JsonCodec

case class FormPreview(
    formId: String,
    scenarios: Vector[PreviewScenario],
) derives JsonCodec

case class GetFormPreviewsResponse(
    previews: Vector[FormPreview],
) derives JsonCodec

/** The states of the built-in forms, with realistic data, that the console previews. The steps mirror what
  * `ConversationRenderService.StepView` serialises; a form that is not listed here (a custom one) is previewed
  * from its own backend properties instead.
  *
  * The consent scenarios use the descriptions that bootstrap seeds for the standard scopes, so what an
  * administrator previews is what a user is shown.
  */
object FormPreviews:

  private def obj(fields: (String, Json)*): Json = Json.Obj(Chunk.from(fields))
  private def str(value: String): Json = Json.Str(value)
  private def bool(value: Boolean): Json = Json.Bool(value)
  private def num(value: Int): Json = Json.Num(value)
  private def arr(values: Json*): Json = Json.Arr(Chunk.from(values))

  private def scenario(id: String, en: String, ru: String, step: Json, error: Option[String] = None): PreviewScenario =
    PreviewScenario(id, Map("en" -> en, "ru" -> ru), step, error)

  private val appUrl = "https://danceliker.example"

  private def region(code: String, dialCode: String, length: Int, regex: String): Json =
    obj("region" -> str(code), "dialCode" -> str(dialCode), "length" -> num(length), "regex" -> str(regex), "registration" -> bool(true))

  private val kz = region("KZ", "+7", 10, """7\d{9}""")
  private val ru = region("RU", "+7", 10, """9\d{9}""")
  private val uz = region("UZ", "+998", 9, """\d{9}""")
  private val kg = region("KG", "+996", 9, """[2-7]\d{8}""")

  private def credential(
      primary: List[String],
      passkey: Boolean = false,
      inlinePassword: Boolean = false,
      phoneRegions: List[Json] = Nil,
      passwordRegex: Option[String] = None,
  ): Json =
    obj(
      List[(String, Json)](
        "type" -> str("credential"),
        "primaryCredentials" -> arr(primary.map(str)*),
        "inlinePassword" -> bool(inlinePassword),
        "passkey" -> bool(passkey),
        "registration" -> bool(false),
      ) ++ Option.when(phoneRegions.nonEmpty)("phoneRegions" -> arr(phoneRegions*)) ++
        passwordRegex.map("passwordRegex" -> str(_))*,
    )

  private val passwordRegex = SystemSettingsRecord.DefaultPasswordRegex

  // The standard scopes, worded as bootstrap seeds them.
  private def scope(token: String, en: String, ru: String, claims: List[(String, String)], deselectable: Boolean): Json =
    obj(
      "scope" -> str(token),
      "description" -> str(en),
      "descriptionLocalizations" -> obj("en" -> str(en), "ru" -> str(ru)),
      "claims" -> arr(claims.map((claimEn, _) => str(claimEn))*),
      "claimLocalizations" -> arr(claims.map((claimEn, claimRu) => obj("en" -> str(claimEn), "ru" -> str(claimRu)))*),
      "deselectable" -> bool(deselectable),
    )

  private val openid =
    scope(
      "openid",
      "OpenID Connect authentication",
      "Аутентификация OpenID Connect",
      List("Unique identifier of the user" -> "Уникальный идентификатор пользователя"),
      false,
    )
  private def profile(deselectable: Boolean) =
    scope(
      "profile",
      "Basic profile information",
      "Основная информация профиля",
      List("Full name" -> "Полное имя", "Profile picture URL" -> "URL изображения профиля", "Locale" -> "Локаль"),
      deselectable,
    )
  private def email(deselectable: Boolean) =
    scope(
      "email",
      "Email address",
      "Адрес электронной почты",
      List("Email address" -> "Адрес электронной почты", "Email address verification status" -> "Статус подтверждения адреса электронной почты"),
      deselectable,
    )
  private def phone(deselectable: Boolean) =
    scope(
      "phone",
      "Phone number",
      "Номер телефона",
      List("Phone number" -> "Номер телефона", "Phone number verification status" -> "Статус подтверждения номера телефона"),
      deselectable,
    )
  private def address(deselectable: Boolean) =
    scope("address", "Postal address", "Почтовый адрес", List("Postal address" -> "Почтовый адрес"), deselectable)
  private val offlineAccess = scope("offline_access", "Long-lived access", "Длительный доступ", Nil, false)

  private val partnerLogo =
    "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64'><rect width='64' height='64' rx='14' fill='%23ff6b4a'/>" +
      "<text x='32' y='43' font-size='30' font-weight='800' text-anchor='middle' fill='white' font-family='sans-serif'>D</text></svg>"

  private def consent(scopes: List[Json], allowPartial: Boolean, extra: (String, Json)*): Json =
    obj(
      List[(String, Json)](
        "type" -> str("consent"),
        "clientName" -> str("Danceliker"),
        "policyUri" -> str(s"$appUrl/privacy"),
        "tosUri" -> str(s"$appUrl/terms"),
        "scopes" -> arr(scopes*),
        "allowPartial" -> bool(allowPartial),
        "denyUri" -> str(s"$appUrl/callback?error=access_denied"),
      ) ++ extra*,
    )

  private def otp(destination: String, resendAfter: Int, lockedSeconds: Option[Int] = None): Json =
    obj(
      List[(String, Json)]("type" -> str("otp"), "length" -> num(6), "resendAfter" -> num(resendAfter), "destination" -> str(destination)) ++
        lockedSeconds.map("lockedSeconds" -> num(_))*,
    )

  private def terminal(stepType: String, redirect: Boolean): Json =
    obj(List[(String, Json)]("type" -> str(stepType)) ++ Option.when(redirect)("redirectUri" -> str(s"$appUrl/callback"))*)

  val all: Vector[FormPreview] = Vector(
    FormPreview(
      "credential",
      Vector(
        scenario("email-password", "Email and password", "Email и пароль", credential(List("email"), passkey = true, inlinePassword = true)),
        scenario("phone", "Phone, one country", "Телефон, одна страна", credential(List("phone"), passkey = true, phoneRegions = List(kz))),
        scenario(
          "phone-countries",
          "Phone, several countries",
          "Телефон, несколько стран",
          credential(List("phone"), passkey = true, phoneRegions = List(kz, ru, uz, kg)),
        ),
        scenario("login-password", "Username and password", "Логин и пароль", credential(List("login"), passwordRegex = Some(passwordRegex))),
        scenario("email-or-phone", "Email or phone", "Email или телефон", credential(List("email", "phone"))),
        scenario(
          "wrong-password",
          "Wrong password",
          "Неверный пароль",
          credential(List("login"), passwordRegex = Some(passwordRegex)),
          Some("login_failed"),
        ),
        scenario("passkey-failed", "Passkey failed", "Ошибка passkey", credential(List("email"), passkey = true), Some("passkey_failed")),
      ),
    ),
    FormPreview(
      "otp",
      Vector(
        scenario("code", "Enter the code", "Ввод кода", otp("a•••a@example.com", resendAfter = 0)),
        scenario("resend-timer", "Resend timer", "Таймер повторной отправки", otp("+7 701 ••• •• 78", resendAfter = 42)),
        scenario("locked", "Locked after attempts", "Блокировка после попыток", otp("+7 701 ••• •• 78", resendAfter = 42, lockedSeconds = Some(20))),
        scenario("wrong-code", "Wrong code", "Неверный код", otp("a•••a@example.com", resendAfter = 0), Some("otp_wrong")),
      ),
    ),
    FormPreview(
      "password",
      Vector(
        scenario("password", "Enter the password", "Ввод пароля", obj("type" -> str("password"), "passwordRegex" -> str(passwordRegex))),
        scenario(
          "wrong-password",
          "Wrong password",
          "Неверный пароль",
          obj("type" -> str("password"), "passwordRegex" -> str(passwordRegex)),
          Some("password_wrong"),
        ),
      ),
    ),
    FormPreview(
      "set-password",
      Vector(
        scenario("set-password", "Choose a password", "Выбор пароля", obj("type" -> str("set-password"), "passwordRegex" -> str(passwordRegex))),
        scenario(
          "reused",
          "Password used recently",
          "Пароль уже использовался",
          obj("type" -> str("set-password"), "passwordRegex" -> str(passwordRegex)),
          Some("password_reused"),
        ),
      ),
    ),
    FormPreview(
      "consent",
      Vector(
        scenario("typical", "Typical request", "Типичный запрос", consent(List(openid, email(true), profile(true)), allowPartial = true)),
        scenario(
          "everything",
          "Everything requested",
          "Запрошено всё",
          consent(List(openid, address(true), email(true), offlineAccess, phone(true), profile(true)), allowPartial = true),
        ),
        scenario(
          "all-required",
          "Nothing optional",
          "Ничего необязательного",
          consent(List(openid, email(false), profile(false)), allowPartial = false),
        ),
        scenario(
          "signed-in-user",
          "With the signed-in user",
          "С вошедшим пользователем",
          consent(List(openid, email(true), profile(true)), allowPartial = true, "user" -> obj("name" -> str("Anna Petrova"))),
        ),
        scenario(
          "partner-logo",
          "With the partner's logo",
          "С логотипом партнёра",
          consent(List(openid, email(true), profile(true)), allowPartial = true, "logoUri" -> str(partnerLogo)),
        ),
      ),
    ),
    FormPreview(
      "passkey-enroll",
      Vector(
        scenario("enroll", "Offer a passkey", "Предложение passkey", obj("type" -> str("passkey-enroll"), "publicKeyOptions" -> str("{}"))),
        scenario(
          "enroll-failed",
          "Enrolment failed",
          "Не удалось создать passkey",
          obj("type" -> str("passkey-enroll"), "publicKeyOptions" -> str("{}")),
          Some("enroll_failed"),
        ),
      ),
    ),
    FormPreview("access-denied", Vector(scenario("access-denied", "Access denied", "Доступ запрещён", terminal("access-denied", redirect = true)))),
    FormPreview(
      "conversation-expired",
      Vector(
        scenario("with-return", "With a way back", "С возвратом", terminal("conversation-expired", redirect = true)),
        scenario("no-return", "Without a way back", "Без возврата", terminal("conversation-expired", redirect = false)),
      ),
    ),
    FormPreview(
      "service-unavailable",
      Vector(
        scenario("with-return", "With a way back", "С возвратом", terminal("service-unavailable", redirect = true)),
        scenario("no-return", "Without a way back", "Без возврата", terminal("service-unavailable", redirect = false)),
      ),
    ),
    FormPreview(
      "signed-out",
      Vector(
        scenario(
          "signed-out",
          "Signed out",
          "Выход выполнен",
          obj("type" -> str("signed-out"), "logoutUris" -> arr(), "redirectUri" -> str(s"$appUrl/")),
        ),
      ),
    ),
    FormPreview(
      "confirm-logout",
      Vector(
        scenario(
          "confirm",
          "Confirm the sign-out",
          "Подтверждение выхода",
          obj(
            "type" -> str("confirm-logout"),
            "csrfToken" -> str("preview"),
            "postLogoutRedirectUri" -> str(s"$appUrl/"),
            "state" -> str("preview"),
          ),
        ),
      ),
    ),
  )

  val response: GetFormPreviewsResponse = GetFormPreviewsResponse(all)
