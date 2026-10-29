package versola.loadgen.antifraud

import java.util.SplittableRandom
import scala.collection.mutable

/** Source addresses for the sessions, as `auth` is told them in the tenant's IP header: private IPv4 addresses
  * (`10.0.0.0/8`), never the same one twice in a run, so that what one scenario gets blocked is not met by the next.
  */
final class Sources(random: SplittableRandom):
  private val used = mutable.HashSet.empty[String]

  /** One address nobody in this run has used. */
  def next(): String =
    var address = draw()
    while !used.add(address) do address = draw()
    address

  /** `size` distinct addresses. */
  def pool(size: Int): Vector[String] = Vector.fill(size)(next())

  private def draw(): String =
    val bits = random.nextInt(1 << 24)
    // .0 and .255 are kept out: a stack may read them as a network or a broadcast address
    s"10.${(bits >>> 16) & 0xff}.${(bits >>> 8) & 0xff}.${1 + (bits & 0xff) % 254}"
