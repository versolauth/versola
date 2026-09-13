package versola.loadgen.protocol

import zio.Task

/** Bootstraps and administers the SUT's configuration -- clients, resources, roles,
  * permissions, auth-request presets, challenge settings -- plus the sync/outbox calls a
  * campaign needs before traffic starts. Used only by `loadgen provision` and `loadgen seed`
  * (§10); never wired into a driver, which is why this lives on its own trait rather than
  * folded into [[AuthClient]].
  *
  * Every operation is a desired-state apply, not a create: `provision` runs against
  * environments that are already provisioned at least as often as against empty ones, and one
  * that failed halfway through has to be re-runnable. The `upsert*` names are the dev spec's
  * (§4); `registerClient`/`registerResource` keep theirs but behave the same way.
  */
trait AdminClient:
  /** Answers the credentials a driver authenticates with, which for a confidential client means
    * a secret this call is the only source of -- central hands one back on registration and
    * never again.
    */
  def registerClient(spec: ClientSpec): Task[ClientCreds]
  def registerResource(spec: ResourceSpec): Task[Unit]
  def upsertRoles(specs: List[RoleSpec]): Task[Unit]
  def upsertPermissions(specs: List[PermissionSpec]): Task[Unit]
  def upsertAuthRequestPresets(spec: AuthRequestPresetsSpec): Task[Unit]
  def upsertChallengeSettings(spec: ChallengeSettingsSpec): Task[Unit]
  def syncConfiguration(): Task[Unit]
  def syncEdgeConfiguration(): Task[Unit]
  def flushUserOutbox(): Task[Unit]
