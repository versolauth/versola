package versola.central.users

import java.time.ZoneId
import scala.jdk.CollectionConverters.*

/** The `zoneinfo` claim of OpenID Connect Core 1.0 §5.1: a name of the IANA time zone database (`Asia/Almaty`, `UTC`). An offset
  * (`+05:00`) and a name the JVM's own tzdata does not carry are not one, and neither is anything with a different spelling of case: the
  * claim is stored and read back as the database writes it. */
object Zoneinfo:
  private val names: Set[String] = ZoneId.getAvailableZoneIds.asScala.toSet

  /** The longest name of the database is under 40 characters; the bound keeps a hostile value out of the lookup, not out of the claim. */
  private val MaxLength = 64

  def isValid(value: String): Boolean = value.length <= MaxLength && names.contains(value)
