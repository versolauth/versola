package versola.central.configuration.tenants

import versola.central.configuration.challenges.SecurityProfile

/** A tenant together with the two settings the console lists beside it, which live in the
  * tenant's challenge settings rather than on the tenant row: which profile its clients are
  * held to, and which published key signs its tokens.
  */
case class TenantSummary(
    tenant: TenantRecord,
    securityProfile: SecurityProfile,
    signingKeyId: Option[String],
)
