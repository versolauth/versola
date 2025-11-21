package versola.util

enum EnvName(val value: String):
  case Prod extends EnvName("prod")
  case Test(override val value: String) extends EnvName(value)
