package versola.util

enum EnvName(val value: String):
  def isProd: Boolean = this == EnvName.Prod
  def isTest: Boolean = !isProd
  
  case Prod extends EnvName("prod")
  case Test(override val value: String) extends EnvName(value)
