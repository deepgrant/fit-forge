package ffmforge.store

import java.time.Instant
import java.util.UUID

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import ffmforge.DownloadFormat
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

  private def keyFor(id: String, format: DownloadFormat = DownloadFormat.Fit): String =
    format match {
      case DownloadFormat.Fit => s"fit/$id"
      case DownloadFormat.Gpx => s"gpx/$id"
    }

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

  def putDerived(id: String, format: DownloadFormat, bytes: Array[Byte]): Future[Either[StoreError, Unit]] = Future {
    blocking {
      ifExpired(id) match {
        case Some(err) => Left(err)
        case None =>
          client.putObject(
            PutObjectRequest
              .builder()
              .bucket(bucket)
              .key(keyFor(id, format))
              .contentType(format.contentType)
              .build(),
            RequestBody.fromBytes(bytes),
          )
          Right(())
      }
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

  def createDownload(
    id: String,
    format: DownloadFormat,
    presignTtl: FiniteDuration,
  ): Future[Either[StoreError, PresignedDownload]] = Future {
    blocking {
      ifExpired(id) match {
        case Some(err) => Left(err)
        case None =>
          try {
            val key      = keyFor(id, format)
            val filename = s"$id.${format.extension}"
            client.headObject { r =>
              r.bucket(bucket)
              r.key(key)
              ()
            }
            val get = GetObjectRequest
              .builder()
              .bucket(bucket)
              .key(key)
              .responseContentDisposition(s"""attachment; filename="$filename"""")
              .responseContentType(format.contentType)
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
                format,
                filename,
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
      deleteAllFormats(id)
    }
  }

  def sweepExpired(): Future[Int] = Future {
    blocking {
      val nowMs = clock().toEpochMilli
      Vector("fit/", "gpx/").map(prefix => sweepExpiredPrefix(prefix, nowMs)).sum
    }
  }

  private def newId(ttl: FiniteDuration): String =
    s"${clock().toEpochMilli + ttl.toMillis}_${UUID.randomUUID()}"

  private def ifExpired(id: String): Option[StoreError] =
    expiryMs(id) match {
      case None => Some(StoreError.NotFound)
      case Some(exp) if clock().toEpochMilli > exp =>
        try deleteAllFormats(id)
        catch { case NonFatal(_) => () }
        Some(StoreError.Expired)
      case Some(_) => None
    }

  private def deleteAllFormats(id: String): Unit =
    DownloadFormat.values.foreach(format =>
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(keyFor(id, format)).build())
    )

  private def sweepExpiredPrefix(prefix: String, nowMs: Long): Int =
    client
      .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
      .contents()
      .asScala
      .map(_.key())
      .filter(k => expiryMs(k.stripPrefix(prefix)).exists(_ < nowMs))
      .map { key =>
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        1
      }
      .sum
}

object S3FitStore {

  def fromDefaultChain(bucket: String, clock: () => Instant)(using ec: ExecutionContext): S3FitStore =
    new S3FitStore(bucket, S3Client.builder().build(), S3Presigner.builder().build(), clock)

  def resolvedRegion(): String = DefaultAwsRegionProviderChain.builder().build().getRegion.id()
}
