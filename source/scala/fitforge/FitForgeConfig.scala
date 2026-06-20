package fitforge

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/**
 * Runtime configuration, sourced from the environment. S3 is real AWS (default-profile creds, us-east-1 by default).
 */
final case class FitForgeConfig(
    port: Int,
    sessionTtl: FiniteDuration,
    staticDir: String,
    s3Bucket: String,
    awsRegion: String,
)

object FitForgeConfig {

  def fromEnv(): FitForgeConfig = {
    def env(key: String): Option[String] = sys.env.get(key).filter(_.nonEmpty)
    FitForgeConfig(
      port = env("PORT").flatMap(_.toIntOption).getOrElse(8080),
      sessionTtl = env("SESSION_TTL_MINUTES").flatMap(_.toIntOption).getOrElse(120).minutes,
      staticDir = env("STATIC_DIR").getOrElse("/app/static"),
      s3Bucket = env("S3_BUCKET").getOrElse("fit-forge-uploads"),
      awsRegion = env("AWS_REGION").getOrElse("us-east-1"),
    )
  }
}
