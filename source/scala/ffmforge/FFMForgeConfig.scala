package ffmforge

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/**
 * Runtime configuration, sourced from the environment. Deployment-specific values are required at runtime and are not
 * hard-coded in repo files.
 */
final case class FFMForgeConfig(
    port: Int,
    sessionTtl: FiniteDuration,
    presignTtl: FiniteDuration,
    staticDir: String,
    s3Bucket: String,
)

object FFMForgeConfig {

  def fromEnv(): FFMForgeConfig = {
    def env(key: String): Option[String] = sys.env.get(key).filter(_.nonEmpty)
    def required(key: String): String =
      env(key).getOrElse(sys.error(s"Missing required environment variable $key"))
    FFMForgeConfig(
      port = env("PORT").flatMap(_.toIntOption).getOrElse(8080),
      sessionTtl = env("SESSION_TTL_MINUTES").flatMap(_.toIntOption).getOrElse(120).minutes,
      presignTtl = env("PRESIGN_TTL_MINUTES").flatMap(_.toIntOption).getOrElse(15).minutes,
      staticDir = env("STATIC_DIR").getOrElse("/app/static"),
      s3Bucket = required("S3_BUCKET"),
    )
  }
}
