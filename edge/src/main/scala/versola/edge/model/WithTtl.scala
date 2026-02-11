package versola.edge.model

import zio.Duration

case class WithTtl[T](value: T, ttl: Duration)

