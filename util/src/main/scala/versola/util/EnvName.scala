package versola.util

/** The environment a service runs in. `Prod` is where real users are: one-time codes are random and delivered,
  * Postgres TLS must verify, the `/service` tooling is absent. Any other name is a test environment, in which
  * codes are fixed and nothing is sent.
  */
enum EnvName(val value: String):
  def isProd: Boolean = this == EnvName.Prod
  def isTest: Boolean = !isProd

  case Prod extends EnvName("prod")
  case Test(override val value: String) extends EnvName(value)

object EnvName:
  /** `prod` and every name ending in it (`eu-prod`, `preprod`) is production. A test environment is what any other name
    * means, so a deployment that serves users must be named for what it is: a name only resembling production
    * (`prd`, `Prod`) would quietly run with fixed one-time codes.
    */
  def parse(value: String): EnvName =
    if value.endsWith("prod") then Prod else Test(value)
