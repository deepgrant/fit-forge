package fitforge.http

import java.time.Instant

import scala.util.Try

import org.apache.pekko.actor.ActorSystem
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

import fitforge.store.S3FitStore

/**
 * Builds an `S3FitStore` against **real AWS S3** for tests: `default`-profile credentials (the AWS Default Credentials
 * Provider Chain), region `us-east-1`, and the bucket created by OpenTofu (`infra/`).
 *
 * Tests gate on [[available]] — if no AWS credentials resolve they are cancelled (not failed), so the suite is green on
 * machines/CI without AWS access. Point `FITFORGE_TEST_BUCKET` at a disposable bucket; objects are short-lived (TTL).
 */
object AwsS3Support {

  val region: String = sys.env.getOrElse("AWS_REGION", "us-east-1")
  val bucket: String = sys.env.getOrElse("FITFORGE_TEST_BUCKET", "fit-forge-uploads")

  /** True when AWS credentials resolve from the default chain (e.g. the `default` profile). */
  lazy val available: Boolean =
    Try { val _ = DefaultCredentialsProvider.builder().build().resolveCredentials(); true }.getOrElse(false)

  def newStore(clock: () => Instant)(using system: ActorSystem): S3FitStore =
    new S3FitStore(bucket, S3FitStore.s3Settings(region), clock)
}
