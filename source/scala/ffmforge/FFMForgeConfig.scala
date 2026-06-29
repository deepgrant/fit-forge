package ffmforge

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/**
 * Runtime configuration, sourced from one HOCON environment block over non-sensitive application defaults.
 * Deployment-specific values are required at runtime and are not hard-coded in repo files.
 */
final case class FFMForgeConfig(
  port: Int,
  sessionTtl: FiniteDuration,
  presignTtl: FiniteDuration,
  staticDir: String,
  s3Bucket: String,
)

object FFMForgeConfig {

  val EnvironmentVariable = "FFMFORGE_CONFIG"
  private val RootPath    = "ffmforge"

  def fromEnv(): FFMForgeConfig =
    fromEnv(sys.env)

  def fromEnv(env: Map[String, String]): FFMForgeConfig = {
    val hocon = env
      .get(EnvironmentVariable)
      .filter(_.trim.nonEmpty)
      .getOrElse(sys.error(s"Missing required HOCON environment variable $EnvironmentVariable"))
    fromHocon(hocon)
  }

  def fromHocon(hocon: String): FFMForgeConfig =
    fromConfig(ConfigFactory.parseString(hocon).withFallback(ConfigFactory.load()).resolve())

  def fromConfig(config: Config): FFMForgeConfig = {
    val root = config.getConfig(RootPath)
    FFMForgeConfig(
      port = root.getInt("port"),
      sessionTtl = duration(root, "session-ttl"),
      presignTtl = duration(root, "presign-ttl"),
      staticDir = root.getString("static-dir"),
      s3Bucket = root.getString("s3.bucket"),
    )
  }

  private def duration(config: Config, path: String): FiniteDuration =
    config.getDuration(path, TimeUnit.MILLISECONDS).millis
}
