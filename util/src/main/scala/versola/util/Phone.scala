package versola.util

import com.google.i18n.phonenumbers.{NumberParseException, PhoneNumberUtil}
import zio.schema.Schema

type Phone = Phone.Type

object Phone:
  given Schema[Phone] = Schema.primitive[String]
    .transformOrFail(parse, Right(_))

  private val util = PhoneNumberUtil.getInstance()
  private val regex = "\\+?\\d{9,15}".r

  opaque type Type <: String = String

  inline def apply(phone: String): Phone = phone

  /** Validates the input and returns it in canonical E.164, the only form that is stored,
    * compared or hashed: inputs that denote one number, such as `+787011234567` with the
    * national trunk prefix and `+77011234567`, yield one value.
    */
  def parse(string: String): Either[String, Phone] = {
    try {
      Option.when(regex.matches(string))(util.parse(string, "ZZ"))
        .filter(util.isValidNumber)
        .map(number => Phone(util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)))
        .toRight(s"$string is invalid phone number")
    } catch {
      case ex: NumberParseException =>
        Left(ex.getMessage)
    }
  }

  /** ISO 3166-1 alpha-2 region of a number, e.g. `KZ` for `+77011234567`; `None` when the
    * value is not a valid number.
    */
  def regionCode(value: Phone): Option[String] =
    try
      Option(util.getRegionCodeForNumber(util.parse(value, "ZZ"))).filter(_ != "ZZ")
    catch
      case _: NumberParseException => None

  /** Keeps the leading `+` and country calling code plus the last two digits,
    * masking everything in between with bullets. Valid phone values are formatted
    * internationally with spaces; unparseable values use the raw representation.
  */
  def mask(value: Phone): String =
    val digits = if value.startsWith("+") then value.tail else value
    val prefixLen = math.min(countryCodeLength(value), math.max(0, digits.length - MinMaskedSuffix))
    if digits.length <= prefixLen + MinMaskedSuffix then
      "+" + "•" * digits.length
    else
      val prefix = digits.take(prefixLen)
      val suffix = digits.takeRight(SuffixLen)
      val maskedLen = digits.length - prefixLen - SuffixLen
      val rawMask = s"+$prefix${"•" * maskedLen}$suffix"
      formatInternational(value)
        .filter(_.count(_.isDigit) == digits.length)
        .fold(rawMask)(maskFormatted(_, prefixLen))

  private def formatInternational(value: Phone): Option[String] =
    try
      val parsed = util.parse(value, "ZZ")
      Some(util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL).replaceAll("[^+0-9]+", " ").trim)
    catch
      case _: NumberParseException => None

  private def maskFormatted(formatted: String, prefixLen: Int): String =
    val totalDigits = formatted.count(_.isDigit)
    var digitIndex = 0
    formatted.map: char =>
      if char.isDigit then
        val visible = digitIndex < prefixLen || digitIndex >= totalDigits - SuffixLen
        digitIndex += 1
        if visible then char else '•'
      else char

  private def countryCodeLength(value: Phone): Int =
    try
      util.parse(value, "ZZ").getCountryCode.toString.length
    catch
      case _: NumberParseException => 1

  private val SuffixLen = 2
  private val MinMaskedSuffix = SuffixLen
