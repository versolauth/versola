package versola.util.delivery

import java.io.{BufferedReader, IOException, InputStreamReader, OutputStreamWriter}
import java.net.{InetAddress, ServerSocket, Socket}
import java.util.Base64
import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.jdk.CollectionConverters.*

/** A just-enough SMTP server for a sender's own tests: it accepts the commands JavaMail sends, offers `AUTH PLAIN`, and
  * keeps what it was given. It is a test double of the protocol, not an implementation of it.
  */
final class FakeSmtpServer private (socket: ServerSocket, silent: Boolean):
  import FakeSmtpServer.*

  private val received = ConcurrentLinkedQueue[Received]()
  private val accepted = ConcurrentLinkedQueue[Socket]()
  private val running = atomic.AtomicBoolean(true)

  val port: Int = socket.getLocalPort

  def messages: List[Received] = received.asScala.toList

  def close(): Unit =
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
      if silent then
        // Accepts the connection and says nothing, as a hung server does.
        Thread.sleep(60000)
      else
        reply("220 fake.example.com ESMTP")
        var user = Option.empty[String]
        var password = Option.empty[String]
        var from = ""
        var recipients = List.empty[String]
        var line = in.readLine()
        while line != null do
          val upper = line.toUpperCase
          if upper.startsWith("EHLO") then
            reply("250-fake.example.com")
            reply("250 AUTH PLAIN")
          else if upper.startsWith("HELO") then reply("250 fake.example.com")
          else if upper.startsWith("AUTH PLAIN") then
            // AUTH PLAIN <base64 of \0user\0password>, sent as the initial response.
            val parts = String(Base64.getDecoder.decode(line.split(" ").last), "UTF-8").split("\u0000", -1)
            user = Some(parts(1))
            password = Some(parts(2))
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
            received.add(Received(user = user, password = password, from = from, recipients = recipients, data = data.toString))
            reply("250 OK queued")
          else if upper == "QUIT" then
            reply("221 Bye")
            client.close()
          else reply("250 OK")
          line = if client.isClosed then null else in.readLine()
    catch case _: Exception => ()
    finally if !client.isClosed then client.close()

object FakeSmtpServer:
  case class Received(user: Option[String], password: Option[String], from: String, recipients: List[String], data: String):
    def header(name: String): Option[String] =
      data.linesIterator.takeWhile(_.nonEmpty).find(_.toLowerCase.startsWith(name.toLowerCase + ":")).map(_.drop(name.length + 1).trim)

    def body: String = data.linesIterator.dropWhile(_.nonEmpty).drop(1).mkString("\n")

  def start(): FakeSmtpServer = FakeSmtpServer(ServerSocket(0, 50, InetAddress.getLoopbackAddress), silent = false)

  /** Accepts connections and never answers. */
  def startSilent(): FakeSmtpServer = FakeSmtpServer(ServerSocket(0, 50, InetAddress.getLoopbackAddress), silent = true)
