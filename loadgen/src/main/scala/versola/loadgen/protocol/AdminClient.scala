package versola.loadgen.protocol

import zio.Task
import zio.json.ast.Json

/** Bootstraps and administers the SUT's configuration -- clients, resources, roles,
  * permissions, auth-request presets, challenge settings -- plus the sync/outbox calls a
  * campaign needs before traffic starts. Used only by `loadgen provision` and `loadgen seed`
  * (§10); never wired into a driver, which is why this lives on its own trait rather than
  * folded into [[AuthClient]].
  *
  * Request/response shapes are `Json.Obj` placeholders for now, not the ~55 concrete admin
  * payloads e2e's `OAuthClient`/`Flows.scala` already encode. Track E replaces every one of
  * these with a typed request built from the shared flow resource files under `flows`
  * (§3.4) once it ports them; the point of this trait for now is the set of admin operations
  * a campaign needs, not their exact wire shape.
  */
trait AdminClient:
  def registerClient(spec: Json.Obj): Task[ClientCreds]
  def registerResource(spec: Json.Obj): Task[Unit]
  def upsertRoles(spec: Json.Obj): Task[Unit]
  def upsertPermissions(spec: Json.Obj): Task[Unit]
  def upsertAuthRequestPresets(spec: Json.Obj): Task[Unit]
  def upsertChallengeSettings(spec: Json.Obj): Task[Unit]
  def syncConfiguration(): Task[Unit]
  def syncEdgeConfiguration(): Task[Unit]
  def flushUserOutbox(): Task[Unit]
