package ffmforge.http

import java.time.Instant

import scala.util.Try

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

import ffmforge.store.S3FitStore

/**
 * Builds an `S3FitStore` against **real AWS S3** for tests. Deployment-specific values must be supplied externally.
 *
 * Tests gate on [[available]] — if no AWS credentials resolve they are cancelled (not failed), so the suite is green on
 * machines/CI without AWS access. Point `FITFORGE_TEST_BUCKET` at the OpenTofu-created data bucket; objects are
 * short-lived (TTL).
 */
object AwsS3Support {

  val bucket: Option[String] = sys.env.get("FITFORGE_TEST_BUCKET").filter(_.nonEmpty)

  /** True when AWS credentials resolve from the default chain (e.g. the `default` profile). */
  lazy val available: Boolean =
    bucket.nonEmpty && Try { val _ = DefaultCredentialsProvider.builder().build().resolveCredentials(); true }
      .getOrElse(false)

  def newStore(clock: () => Instant)(using scala.concurrent.ExecutionContext): S3FitStore =
    S3FitStore.fromDefaultChain(bucket.getOrElse("missing-test-bucket"), clock)
}
