package versola.edge

import com.typesafe.config.{Config, ConfigFactory}
import versola.edge.model.{ClientId, EdgeCredentials, ScopeToken}
import versola.util.Secret
import zio.{Task, UIO, ZIO, ZLayer}

import scala.jdk.CollectionConverters.*

trait EdgeCredentialsService:
  def getCredentials(clientId: ClientId): UIO[Option[EdgeCredentials]]
  
  def getAllCredentials(): UIO[List[EdgeCredentials]]

object EdgeCredentialsService:
  def live: ZLayer[Any, Throwable, EdgeCredentialsService] =
    ZLayer.fromZIO(loadFromConfig.map(Impl(_)))

  private def loadFromConfig: Task[List[EdgeCredentials]] =
    ZIO.attempt {
      // Load configuration from edge/dev/env.conf or environment variable
      val configString = sys.env.get("EDGE_CONFIG").getOrElse {
        // Try to load from file
        val configPath = sys.props.get("edge.config.path")
          .getOrElse("edge/dev/env.conf")
        
        try {
          val source = scala.io.Source.fromFile(configPath)
          try source.mkString finally source.close()
        } catch {
          case _: java.io.FileNotFoundException =>
            // Return empty config if file doesn't exist
            "edge { clients = [] }"
        }
      }
      
      val config = ConfigFactory.parseString(configString)
      
      if !config.hasPath("edge.clients") then
        List.empty
      else
        val clientsConfig = config.getConfigList("edge.clients").asScala.toList
        
        clientsConfig.map { clientConfig =>
          val clientId = ClientId(clientConfig.getString("client_id"))
          val clientSecret = Secret(clientConfig.getString("client_secret").getBytes("UTF-8"))
          val providerUrl = clientConfig.getString("provider_url")
          val scopes = clientConfig.getStringList("scopes").asScala.toSet.map(ScopeToken(_))
          
          EdgeCredentials(
            clientId = clientId,
            clientSecret = clientSecret,
            providerUrl = providerUrl,
            scopes = scopes,
          )
        }
    }

  class Impl(credentials: List[EdgeCredentials]) extends EdgeCredentialsService:
    private val credentialsMap: Map[ClientId, EdgeCredentials] =
      credentials.map(c => c.clientId -> c).toMap

    override def getCredentials(clientId: ClientId): UIO[Option[EdgeCredentials]] =
      ZIO.succeed(credentialsMap.get(clientId))

    override def getAllCredentials(): UIO[List[EdgeCredentials]] =
      ZIO.succeed(credentials)

