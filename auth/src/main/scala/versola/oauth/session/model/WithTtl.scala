package versola.oauth.session.model

import zio.Duration

case class WithTtl[T](value: T, ttl: Duration)
