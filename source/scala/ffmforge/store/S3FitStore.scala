package ffmforge.store

import java.time.Instant
import java.util.UUID

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.apache.pekko.http.scaladsl.model.StatusCodes
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

/**
 * [[FitStore]] backed by real AWS S3. Credentials and region resolve from the AWS default provider chains. Object ids
 * encode expiry (`<expiresAtMs>_<uuid>`) so Lambda can reject stale sessions without separate metadata.
 */
final class S3FitStore(bucket: String, client: S3Client, presigner: S3Presigner, clock: () => Instant)(using
    ec: ExecutionContext
) extends FitStore {

  private def keyFor(id: String): String         = s"fit/$id"
  private def expiryMs(id: String): Option[Long] = id.takeWhile(_ != '_').toLongOption

  def createUpload(ttl: FiniteDuration, presignTtl: FiniteDuration): Future[PresignedUpload] = Future {
    blocking {
      val id        = newId(ttl)
      val expiresAt = clock().plusMillis(presignTtl.toMillis)
      val put = PutObjectRequest
        .builder()
        .bucket(bucket)
        .key(keyFor(id))
        .build()
      val req = PutObjectPresignRequest
        .builder()
        .signatureDuration(java.time.Duration.ofMillis(presignTtl.toMillis))
        .putObjectRequest(put)
        .build()
      PresignedUpload(id, presigner.presignPutObject(req).url().toString, expiresAt)
    }
  }

  def put(bytes: Array[Byte], ttl: FiniteDuration): Future[String] = Future {
    blocking {
      val id = newId(ttl)
      client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(keyFor(id)).build(),
        RequestBody.fromBytes(bytes),
      )
      id
    }
  }

  def get(id: String): Future[Either[StoreError, Array[Byte]]] = Future {
    blocking {
      ifExpired(id) match {
        case Some(err) => Left(err)
        case None =>
          try {
            val bytes: ResponseBytes[GetObjectResponse] =
              client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(keyFor(id)).build())
            Right(bytes.asByteArray()): Either[StoreError, Array[Byte]]
          } catch {
            case _: NoSuchKeyException                                             => Left(StoreError.NotFound)
            case e: S3Exception if e.statusCode() == StatusCodes.NotFound.intValue => Left(StoreError.NotFound)
          }
      }
    }
  }

  def createDownload(id: String, presignTtl: FiniteDuration): Future[Either[StoreError, PresignedDownload]] = Future {
    blocking {
      ifExpired(id) match {
        case Some(err) => Left(err)
        case None =>
          try {
            client.headObject { r =>
              r.bucket(bucket)
              r.key(keyFor(id))
              ()
            }
            val get = GetObjectRequest
              .builder()
              .bucket(bucket)
              .key(keyFor(id))
              .responseContentDisposition(s"""attachment; filename="$id.fit"""")
              .build()
            val req = GetObjectPresignRequest
              .builder()
              .signatureDuration(java.time.Duration.ofMillis(presignTtl.toMillis))
              .getObjectRequest(get)
              .build()
            Right(
              PresignedDownload(
                id,
                presigner.presignGetObject(req).url().toString,
                clock().plusMillis(presignTtl.toMillis),
              )
            )
          } catch {
            case _: NoSuchKeyException                                             => Left(StoreError.NotFound)
            case e: S3Exception if e.statusCode() == StatusCodes.NotFound.intValue => Left(StoreError.NotFound)
          }
      }
    }
  }

  def delete(id: String): Future[Unit] = Future {
    blocking {
      val _ = client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(keyFor(id)).build())
    }
  }

  def sweepExpired(): Future[Int] = Future {
    blocking {
      val nowMs = clock().toEpochMilli
      client
        .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix("fit/").build())
        .contents()
        .asScala
        .map(_.key())
        .filter(k => expiryMs(k.stripPrefix("fit/")).exists(_ < nowMs))
        .map { key =>
          client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
          1
        }
        .sum
    }
  }

  private def newId(ttl: FiniteDuration): String =
    s"${clock().toEpochMilli + ttl.toMillis}_${UUID.randomUUID()}"

  private def ifExpired(id: String): Option[StoreError] =
    expiryMs(id) match {
      case None => Some(StoreError.NotFound)
      case Some(exp) if clock().toEpochMilli > exp =>
        try client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(keyFor(id)).build())
        catch { case NonFatal(_) => () }
        Some(StoreError.Expired)
      case Some(_) => None
    }
}

object S3FitStore {

  def fromDefaultChain(bucket: String, clock: () => Instant)(using ec: ExecutionContext): S3FitStore =
    new S3FitStore(bucket, S3Client.builder().build(), S3Presigner.builder().build(), clock)

  def resolvedRegion(): String = DefaultAwsRegionProviderChain.builder().build().getRegion.id()
}
