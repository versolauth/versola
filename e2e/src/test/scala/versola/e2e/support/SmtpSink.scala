package versola.e2e.support

import zio.*

import java.io.{BufferedReader, IOException, InputStreamReader, OutputStreamWriter}
import java.net.{InetAddress, ServerSocket, Socket}
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/** A mail server for a test to send to: it takes the messages auth's SMTP client sends and keeps them, so a test can read
  * what was delivered. It speaks just enough of the protocol (`EHLO`, `AUTH PLAIN`, `MAIL`, `RCPT`, `DATA`, `QUIT`) for
  * that, and is written here, in test code, because a mail-server library would be a dependency for one spec.
  *
  * It listens on the loopback interface only, so the stack under test has to run on the same host, as it does in CI and
  * in the staged local stack.
  */
final class SmtpSink private (socket: ServerSocket):
  import SmtpSink.*

  private val received = ConcurrentLinkedQueue[Mail]()
  private val accepted = ConcurrentLinkedQueue[Socket]()
  private val running = AtomicBoolean(true)

  val port: Int = socket.getLocalPort

  def messages: UIO[List[Mail]] = ZIO.succeed(received.asScala.toList)

  /** Waits until at least `count` messages have arrived, or fails after `within`. */
  def awaitMessages(count: Int, within: Duration = 15.seconds): Task[List[Mail]] =
    messages
      .repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil[List[Mail]](_.length >= count))
      .timeoutFail(RuntimeException(s"the mail sink received fewer than $count message(s) within $within"))(within)

  private def close(): Unit =
    running.set(false)
    socket.close()
    accepted.asScala.foreach(client =>
      try client.close()
      catch case _: IOException => (),
    )

  private val acceptor = Thread(() =>
    while running.get() do
      try
        val client = socket.accept()
        accepted.add(client)
        val handler = Thread(() => serve(client))
        handler.setDaemon(true)
        handler.start()
      catch case _: IOException => (),
  )
  acceptor.setDaemon(true)
  acceptor.start()

  private def serve(client: Socket): Unit =
    try
      val in = BufferedReader(InputStreamReader(client.getInputStream))
      val out = OutputStreamWriter(client.getOutputStream)
      def reply(line: String): Unit =
        out.write(line + "\r\n")
        out.flush()
      reply("220 sink.example.test ESMTP")
      var user = Option.empty[String]
      var password = Option.empty[String]
      var from = ""
      var recipients = List.empty[String]
      var line = in.readLine()
      while line != null do
        val upper = line.toUpperCase
        if upper.startsWith("EHLO") then
          reply("250-sink.example.test")
          reply("250 AUTH PLAIN")
        else if upper.startsWith("HELO") then reply("250 sink.example.test")
        else if upper.startsWith("AUTH PLAIN") then
          val credentials = String(Base64.getDecoder.decode(line.split(" ").last), "UTF-8").split("\u0000", -1)
          user = Some(credentials(1))
          password = Some(credentials(2))
          reply("235 2.7.0 Authentication successful")
        else if upper.startsWith("MAIL FROM") then
          from = line.substring(line.indexOf('<') + 1, line.indexOf('>'))
          reply("250 OK")
        else if upper.startsWith("RCPT TO") then
          recipients = recipients :+ line.substring(line.indexOf('<') + 1, line.indexOf('>'))
          reply("250 OK")
        else if upper == "DATA" then
          reply("354 End data with <CR><LF>.<CR><LF>")
          val data = StringBuilder()
          var dataLine = in.readLine()
          while dataLine != null && dataLine != "." do
            data.append(dataLine).append("\n")
            dataLine = in.readLine()
          received.add(Mail(user = user, password = password, from = from, recipients = recipients, data = data.toString))
          reply("250 OK queued")
        else if upper == "QUIT" then
          reply("221 Bye")
          client.close()
        else reply("250 OK")
        line = if client.isClosed then null else in.readLine()
    catch case _: Exception => ()
    finally if !client.isClosed then client.close()

object SmtpSink:
  case class Mail(user: Option[String], password: Option[String], from: String, recipients: List[String], data: String):
    def header(name: String): Option[String] =
      data.linesIterator.takeWhile(_.nonEmpty).find(_.toLowerCase.startsWith(name.toLowerCase + ":")).map(_.drop(name.length + 1).trim)

    def body: String = data.linesIterator.dropWhile(_.nonEmpty).drop(1).mkString("\n")

  /** A sink on a free loopback port, closed when the scope ends. */
  val scoped: ZIO[Scope, Nothing, SmtpSink] =
    ZIO.acquireRelease(ZIO.succeed(SmtpSink(ServerSocket(0, 50, InetAddress.getLoopbackAddress))))(sink => ZIO.succeed(sink.close()))
