package versola.loadgen.antifraud

import java.util.SplittableRandom

/** Numbers for the scenarios, made to fall in the ranges the SMS budget counts by.
  *
  * A range is the first `rangeLength` digits of the number with the country code, which is what `SmsBudget` keys
  * on (`antifraud.sms-budget.prefix-length`).
  */
final class PhoneNumbers(plan: NumberPlanConfig, random: SplittableRandom):
  private val dialDigits = plan.dialCode.filter(_.isDigit)

  /** A number in any range the plan allows. */
  def any(): String = number(plan.leads(random.nextInt(plan.leads.size)), plan.nationalLength)

  /** A range to aim an attack at: the country code, a lead and as many further digits as make `rangeLength`. */
  def anyRange(): String =
    val lead = plan.leads(random.nextInt(plan.leads.size))
    val national = lead + randomDigits(plan.rangeLength - dialDigits.length - lead.length)
    dialDigits + national

  /** A number inside `range`, which [[anyRange]] made. */
  def in(range: String): String =
    val national = range.drop(dialDigits.length)
    plan.dialCode + national + randomDigits(plan.nationalLength - national.length)

  def rangeOf(phone: String): String = phone.filter(_.isDigit).take(plan.rangeLength)

  private def number(lead: String, length: Int): String = plan.dialCode + lead + randomDigits(length - lead.length)

  private def randomDigits(count: Int): String =
    val builder = StringBuilder()
    var left = count
    while left > 0 do
      builder.append(('0' + random.nextInt(10)).toChar)
      left -= 1
    builder.toString
