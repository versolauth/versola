package versola.loadgen.provision

import zio.json.ast.Json
import zio.{Task, ZIO}

import java.nio.charset.StandardCharsets

/** Reads the shared flow documents of dev spec §3.4 off the classpath.
  *
  * They are resources rather than Scala values so that e2e can load the same files once
  * `protocol/` is extracted into its own module (§3.2's post-v1 item): two copies of a client's
  * `authFlow` drift the moment central's schema changes, and the drift is invisible until a
  * campaign's logins start failing for a reason the e2e suite says cannot happen.
  */
object FlowResources:

  private val directory = "flows"

  val phoneOtpAuthFlowFile = "phone-otp-auth-flow.json"
  val phoneOtpPasswordAuthFlowFile = "phone-otp-password-auth-flow.json"
  val phonePasskeyAuthFlowFile = "phone-passkey-auth-flow.json"
  val registrationFlowFile = "phone-otp-password-registration-flow.json"

  def load: Task[CampaignFlows] =
    for
      phoneOtp <- read(phoneOtpAuthFlowFile)
      phoneOtpPassword <- read(phoneOtpPasswordAuthFlowFile)
      phonePasskey <- read(phonePasskeyAuthFlowFile)
      registration <- read(registrationFlowFile)
    yield CampaignFlows(
      phoneOtpAuthFlow = phoneOtp,
      phoneOtpPasswordAuthFlow = phoneOtpPassword,
      phonePasskeyAuthFlow = phonePasskey,
      registrationFlow = registration,
    )

  private def read(name: String): Task[Json] =
    val path = s"$directory/$name"
    for
      bytes <- ZIO
        .attemptBlocking(Option(getClass.getClassLoader.getResourceAsStream(path)))
        .someOrFail(MissingFlowResource(path))
        .flatMap(stream => ZIO.attemptBlocking(stream.readAllBytes()).ensuring(ZIO.succeed(stream.close())))
      json <- ZIO
        .fromEither(Json.decoder.decodeJson(String(bytes, StandardCharsets.UTF_8)))
        .mapError(error => MalformedFlowResource(path, error))
    yield json

final case class MissingFlowResource(path: String)
    extends RuntimeException(s"Flow resource '$path' is not on the classpath")

final case class MalformedFlowResource(path: String, detail: String)
    extends RuntimeException(s"Flow resource '$path' is not valid JSON: $detail")
