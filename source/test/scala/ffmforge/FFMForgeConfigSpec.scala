package ffmforge

import scala.concurrent.duration.DurationInt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FFMForgeConfigSpec extends AnyFunSuite with Matchers {

  test("loads runtime configuration from one HOCON block") {
    val config = FFMForgeConfig.fromEnv(
      Map(
        FFMForgeConfig.EnvironmentVariable ->
          """
            ffmforge {
              port = 9090
              static-dir = "/tmp/static"
              session-ttl = 45 minutes
              presign-ttl = 5 minutes
              s3 {
                bucket = "ffmforge-test-data"
              }
            }
          """
      )
    )

    config.port shouldBe 9090
    config.staticDir shouldBe "/tmp/static"
    config.sessionTtl shouldBe 45.minutes
    config.presignTtl shouldBe 5.minutes
    config.s3Bucket shouldBe "ffmforge-test-data"
  }

  test("uses non-sensitive defaults from application.conf") {
    val config = FFMForgeConfig.fromHocon("""
      ffmforge.s3.bucket = "ffmforge-test-data"
    """)

    config.port shouldBe 8080
    config.staticDir shouldBe "/app/static"
    config.sessionTtl shouldBe 120.minutes
    config.presignTtl shouldBe 15.minutes
    config.s3Bucket shouldBe "ffmforge-test-data"
  }
}
