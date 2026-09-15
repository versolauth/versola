package versola.loadgen.seed

import zio.{Chunk, Task, ZIO}

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The tripwire half of the dev spec §3.4 anti-drift guard: a checked-in fingerprint of the SUT
  * migrations directories the seeder is coupled to.
  *
  * §3.4 specifies "a checked-in hash of the migrations directory listing". A listing hash fires
  * on a *new* file and stays silent on an edit to an existing one, and an edit is the more
  * dangerous of the two -- a new migration is a visible event, whereas
  * `ALTER TABLE users ADD COLUMN ... NOT NULL` appended to a migration nobody has applied to
  * production yet looks like nothing at all. So this hashes **names and contents**: the
  * fingerprint is `SHA-256` over `name:sha256(content)` for every `.sql` file, sorted by name.
  *
  * Being a fingerprint, its only possible output is "a human must look". Two things follow, both
  * accepted deliberately:
  *
  *   - It fires on changes that cannot affect the seeder -- a comment, a table it never touches.
  *     False positives are the correct direction to err: the cost is reading a diff, whereas a
  *     false negative is a 1M-user population the real login flow rejects, discovered hours into
  *     a campaign.
  *   - It is silenced by re-recording one file, which is one command. Nothing here can stop that.
  *     What it can do is make the re-recording visible in the diff and say, in the failure
  *     message, what to check before doing it -- and leave the part that does not depend on a
  *     human to [[SutSchemaGuard]].
  *
  * Which directories are covered is [[SutSchema.fingerprintedMigrationDirectories]], derived
  * from the tables the seeder actually writes rather than listed here.
  */
object MigrationFingerprint:

  val expectationResource: String = "seed/sut-migrations.sha256"

  case class Mismatch(directory: String, expected: Option[String], actual: String):
    def message: String = expected match
      case Some(value) => s"$directory: recorded $value, found $actual"
      case None => s"$directory: no recorded fingerprint, found $actual"

  /** sbt and the staged image disagree about the working directory, and so do a run from the
    * repository root and one from the module directory -- the same two candidates
    * `MigrationNamingSpec` and `LoadgenPostgresSpec` already resolve between.
    */
  private def resolve(directory: String): Path =
    List(Path.of(directory), Path.of("..").resolve(directory))
      .find(Files.isDirectory(_))
      .getOrElse(Path.of(directory))

  /** Fails rather than answering for an absent or empty directory. A fingerprint of nothing is a
    * stable value that matches itself forever, which is the one outcome worse than no guard: the
    * check would pass in an environment where the migrations are not on disk at all.
    */
  def of(directory: String): Task[String] =
    ZIO.attemptBlocking:
      val path = resolve(directory)
      if !Files.isDirectory(path) then
        throw IllegalStateException(
          s"Cannot fingerprint '$directory': no such directory (resolved to ${path.toAbsolutePath}). " +
            "The §3.4 guard needs the SUT's migrations on disk; it must fail rather than pass vacuously.",
        )
      val files = Using.resource(Files.list(path)): entries =>
        entries.iterator().asScala
          .filter(entry => Files.isRegularFile(entry) && entry.getFileName.toString.endsWith(".sql"))
          .map(entry => entry.getFileName.toString -> Files.readAllBytes(entry))
          .toList
          .sortBy(_._1)
      if files.isEmpty then
        throw IllegalStateException(s"Cannot fingerprint '$directory': it holds no .sql migrations")
      val digest = MessageDigest.getInstance("SHA-256")
      files.foreach: (name, content) =>
        digest.update(name.getBytes(StandardCharsets.UTF_8))
        digest.update(':'.toByte)
        digest.update(sha256(content))
        digest.update('\n'.toByte)
      hex(digest.digest())

  /** The recorded expectations, as `<directory> <hex>` lines. Read from the classpath rather than
    * the filesystem so it travels with the staged jar, and so the seeder can check itself at
    * startup in a deployment where the migrations *are* mounted.
    */
  def recorded: Task[Map[String, String]] =
    ZIO.attemptBlocking:
      val stream: InputStream = Option(getClass.getClassLoader.getResourceAsStream(expectationResource))
        .getOrElse(throw IllegalStateException(s"Missing resource '$expectationResource'"))
      Using.resource(stream): input =>
        String(input.readAllBytes(), StandardCharsets.UTF_8)
          .linesIterator
          .map(_.trim)
          .filter(line => line.nonEmpty && !line.startsWith("#"))
          .map: line =>
            line.split("\\s+", 2) match
              case Array(directory, value) => directory -> value
              case _ => throw IllegalStateException(s"Malformed fingerprint line: '$line'")
          .toMap

  /** What [[check]] found: the directories that disagree with their recorded value, and the ones
    * that are not on this filesystem to be checked at all.
    *
    * Two fields rather than one list because they call for opposite responses, and a caller that
    * has to name both cannot quietly forget the second -- see [[Seeder.preflight]].
    */
  case class Check(mismatches: Chunk[Mismatch], absent: List[String]):
    def checked: Boolean = absent.isEmpty

  /** Every covered directory's fingerprint against its recorded value, and which directories are
    * absent. An unrecorded but present directory is a mismatch, not a pass -- adding a seeded
    * table in a new service must fail until its fingerprint is recorded.
    *
    * Absence is reported rather than raised, and that is the one concession this layer makes to
    * where it runs. The fingerprint reads the SUT's *source* migrations, which exist in a
    * checkout and in CI but not in `versola-loadgen`'s image -- that stages `/app` and
    * `loadgen/migrations`, and carrying auth's and central's source trees into it would be a
    * second copy of two schemas nobody would keep current. Raising here instead would make the
    * mandatory pre-flight fail in the only place the seeder is meant to run. [[of]] still
    * refuses a directory it is asked for and cannot find, so a half-present tree cannot pass
    * vacuously, and layer 2 -- `SutSchemaGuard`, which reads the live database rather than any
    * file -- is unconditional either way.
    */
  def check: Task[Check] =
    for
      expectations <- recorded
      directories = SutSchema.fingerprintedMigrationDirectories
      absent = directories.filterNot(directory => Files.isDirectory(resolve(directory)))
      results <- ZIO.foreach(Chunk.fromIterable(directories.filterNot(absent.contains))): directory =>
        of(directory).map(actual => (directory, expectations.get(directory), actual))
    yield Check(
      mismatches = results.collect:
        case (directory, expected, actual) if !expected.contains(actual) =>
          Mismatch(directory, expected, actual)
      ,
      absent = absent,
    )

  def absentReport(absent: List[String]): String =
    "Skipping the SUT migration fingerprint (dev spec §3.4 layer 1): not on this filesystem -- " +
      absent.mkString(", ") +
      ". Expected in a repository checkout and in CI, absent in the staged image; the live schema " +
      "check runs either way and is the layer that refuses a drifted column."

  def report(mismatches: Chunk[Mismatch]): String =
    "The SUT migrations the seeder is coupled to have changed (dev spec §3.4):\n" +
      mismatches.map(mismatch => "  - " + mismatch.message).mkString("\n") +
      "\nRead the migration diff and decide whether versola.loadgen.seed.SutSchema and SeedRows " +
      s"need to change. Only then re-record $expectationResource with the 'found' values above."

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  private def hex(bytes: Array[Byte]): String =
    val out = StringBuilder(bytes.length * 2)
    bytes.foreach(byte => out.append(f"${byte & 0xff}%02x"))
    out.toString
