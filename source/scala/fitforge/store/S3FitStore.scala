package fitforge.store

import java.time.Instant
import java.util.UUID

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.connectors.s3.S3Attributes
import org.apache.pekko.stream.connectors.s3.S3Settings
import org.apache.pekko.stream.connectors.s3.scaladsl.S3
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

/**
 * [[FitStore]] backed by AWS S3 via Pekko Connectors (Alpakka). The app enforces a short TTL by encoding `expiresAt`
 * (epoch millis) in the object key (`fit/<expiresAtMs>_<uuid>`), so `get`/`sweepExpired` need no metadata reads; the S3
 * bucket's 1-day lifecycle rule (provisioned by OpenTofu) is the hard backstop. `clock` is injectable for tests.
 *
 * Credentials come from the AWS Default Credentials Provider Chain (the `default` profile locally, IAM role in the
 * cloud). The bucket is created by OpenTofu, not by this code.
 */
final class S3FitStore(bucket: String, settings: S3Settings, clock: () => Instant)(implicit
    system: ActorSystem
) extends FitStore {

  private implicit val ec: ExecutionContext = system.dispatcher
  private val attrs                         = S3Attributes.settings(settings)

  private def keyFor(id: String): String         = s"fit/$id"
  private def expiryMs(id: String): Option[Long] = id.takeWhile(_ != '_').toLongOption

  def put(bytes: Array[Byte], ttl: FiniteDuration): Future[String] = {
    val id = s"${clock().toEpochMilli + ttl.toMillis}_${UUID.randomUUID()}"
    Source
      .single(ByteString.fromArray(bytes))
      .runWith(S3.multipartUpload(bucket, keyFor(id)).withAttributes(attrs))
      .map(_ => id)
  }

  def get(id: String): Future[Either[StoreError, Array[Byte]]] =
    expiryMs(id) match {
      case None                                    => Future.successful(Left(StoreError.NotFound))
      case Some(exp) if clock().toEpochMilli > exp => delete(id).map(_ => Left(StoreError.Expired))
      case Some(_) =>
        S3.getObject(bucket, keyFor(id))
          .withAttributes(attrs)
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .map(bs => Right(bs.toArray): Either[StoreError, Array[Byte]])
          .recover { case _ => Left(StoreError.NotFound) }
    }

  def delete(id: String): Future[Unit] =
    S3.deleteObject(bucket, keyFor(id)).withAttributes(attrs).runWith(Sink.ignore).map(_ => ())

  def sweepExpired(): Future[Int] = {
    val nowMs = clock().toEpochMilli
    S3.listBucket(bucket, Some("fit/"))
      .withAttributes(attrs)
      .map(_.key)
      .filter(k => expiryMs(k.stripPrefix("fit/")).exists(_ < nowMs))
      .mapAsync(4)(k => S3.deleteObject(bucket, k).withAttributes(attrs).runWith(Sink.ignore).map(_ => 1))
      .runWith(Sink.fold(0)(_ + _))
  }
}

object S3FitStore {

  /** S3 settings for real AWS: region + the Default Credentials Provider Chain (no endpoint override). */
  def s3Settings(region: String)(implicit system: ActorSystem): S3Settings = {
    val regionProvider: AwsRegionProvider = () => Region.of(region)
    S3Settings(system)
      .withS3RegionProvider(regionProvider)
      .withCredentialsProvider(DefaultCredentialsProvider.builder().build())
  }
}
